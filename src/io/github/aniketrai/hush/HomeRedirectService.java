package io.github.aniketrai.hush;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the Home button land on Hush.
 *
 * Android TV 9 always resolves Home to Google's launcher (system priority 2 beats any installed
 * app), and after a Home press it delays activity starts from background apps by ~5 s. So:
 *
 * 1. Home key: we catch it before Android handles it and open Hush directly (no delay).
 *    Every other key passes straight through untouched; nothing is stored or logged.
 * 2. Escape hatch: press Home twice quickly and it goes to Google's home as normal, and the
 *    redirect stands aside for 10 minutes.
 * 3. Fallback: if Google's home appears some other way (e.g. at boot), switch to Hush.
 *    If Hush keeps closing, back off and leave Google's home in place.
 *
 * Window events are limited to com.google.android.tvlauncher (res/xml/redirect_service.xml),
 * and the service cannot read window content.
 */
public class HomeRedirectService extends AccessibilityService {
    private static final String TAG = "SimpleHome";
    private static final String GOOGLE_HOME_ACTIVITY = "com.google.android.tvlauncher.MainActivity";

    private static final long DOUBLE_PRESS_MS = 600;
    private static final long ESCAPE_MS = 10 * 60_000;

    // Crash-loop guard for the window fallback.
    private static final long BOUNCE_MS = 2_500;
    private static final int MAX_BOUNCES = 3;
    private static final long PAUSE_MS = 120_000;

    private long lastHomeUp = 0;
    private long standAsideUntil = 0;
    private long lastRedirect = 0;
    private int bounces = 0;
    private long pausedUntil = 0;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        long now = SystemClock.uptimeMillis();
        if (now < standAsideUntil) return false;

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (now - lastHomeUp < DOUBLE_PRESS_MS) {
                // Double press: hand Home back to Android (Google's home) and stay out of the way.
                lastHomeUp = 0;
                standAsideUntil = now + ESCAPE_MS;
                Log.i(TAG, "Home pressed twice: showing Google home, redirect off for 10 minutes");
                performGlobalAction(GLOBAL_ACTION_HOME);
                return true;
            }
            lastHomeUp = now;
            if (!openSimpleHome()) return false;
        }
        return true; // consume both down and up so Android doesn't also go to Google's home
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        // Only the home screen itself: Google's own settings pages still open normally.
        if (cls == null || !GOOGLE_HOME_ACTIVITY.contentEquals(cls)) return;

        long now = SystemClock.uptimeMillis();
        if (now < pausedUntil || now < standAsideUntil) return;

        bounces = (lastRedirect != 0 && now - lastRedirect < BOUNCE_MS) ? bounces + 1 : 0;
        if (bounces >= MAX_BOUNCES) {
            pausedUntil = now + PAUSE_MS;
            bounces = 0;
            Log.w(TAG, "Hush keeps closing; leaving Google home for 2 minutes");
            return;
        }
        lastRedirect = now;
        openSimpleHome();
    }

    private boolean openSimpleHome() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(home);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not open Hush; leaving Google home in place", e);
            return false;
        }
    }

    @Override
    public void onInterrupt() {
    }
}
