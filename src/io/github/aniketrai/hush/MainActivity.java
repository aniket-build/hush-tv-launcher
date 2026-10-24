package io.github.aniketrai.hush;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hush: clock, a Settings button, and "Recently played": the 7 most recently used apps
 * (4 per row) plus an 8th "More Apps" tile that opens every other app A–Z (MoreAppsActivity,
 * which is this same screen in "more" mode). Plain framework views so it starts fast and stays
 * small on a 32-bit, 2 GB TV.
 *
 * "Recently used" = the latest of (a) when you last opened the app from here, and
 * (b) Android's usage stats, if Usage access has been granted (optional; empty otherwise).
 */
public class MainActivity extends Activity {
    private static final int COLUMNS = 4;
    private static final int RECENT_COUNT = 7;
    private static final String PREFS = "home";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_MOTION = "background_motion";
    private static final String LAST_USED = "t:";
    private static final String SETTINGS = "__settings__";
    private static final String MORE = "__more__";
    private static final int AMBER = 0xFFF5A524;
    private static final long USAGE_LOOKBACK_MS = 90L * 24 * 60 * 60 * 1000;

    /** Order for apps with no history yet (edit to taste); everything else follows A–Z. */
    private static final List<String> SEED_ORDER = Arrays.asList(
            "com.netflix.ninja",
            "in.startv.hotstar",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.apple.atve.androidtv.appletv",
            "com.graymatrix.did",
            "tv.airtel.xstream.tvapp",
            "com.fancode.tv",
            "com.ted.android.tv",
            "org.videolan.vlc");

    /** Other launchers: opening them would just bounce back here via the redirect. */
    private static final List<String> HIDDEN_ALWAYS = Arrays.asList(
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher");

    static final class App {
        String pkg;
        CharSequence label;
        Drawable banner;
        Drawable icon;
        Intent launch;
        long lastUsed;
    }

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final List<String> shownOrder = new ArrayList<>();
    private SharedPreferences prefs;
    private GridLayout grid;
    private ScrollView scroll;
    private TextView caption;
    private AuroraView aurora;
    private boolean resumed;
    private BroadcastReceiver packageReceiver;
    private float density;
    private int tileW;
    private int tileH;
    private int gap;

    /** MoreAppsActivity overrides this to show the remaining apps instead of the recent ones. */
    protected boolean isMoreScreen() {
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());

        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                loadApps();
            }
        };
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateMotion();
        if (!apps.isEmpty()) refreshOrder(true);
    }

    @Override
    protected void onPause() {
        resumed = false;
        updateMotion();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Screensaver, dialogs or another app on top: stop drawing the aurora.
        updateMotion();
    }

    /** The aurora drifts only while Home is visible and focused, and motion is on. */
    private void updateMotion() {
        if (aurora == null) return;
        aurora.setRunning(resumed && hasWindowFocus() && prefs.getBoolean(KEY_MOTION, true));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already here: back to the most recent app.
        focusFirst();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back has nowhere to go. More Apps: back to Home.
        if (isMoreScreen()) finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null) return f.performLongClick();
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        // TV safe area: 48dp at the sides, 27dp top/bottom; content stays inside it.
        int sideInset = dp(48);
        int gridPad = dp(10); // headroom inside the scroller for the focus zoom
        int usable = getResources().getDisplayMetrics().widthPixels - 2 * sideInset - 2 * gridPad;
        gap = dp(18);
        tileW = (usable - (COLUMNS - 1) * gap) / COLUMNS;
        tileH = tileW * 9 / 16;

        FrameLayout root = new FrameLayout(this);

        aurora = new AuroraView(this);
        root.addView(aurora, match());


        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x73000000, 0x0D000000, 0x14000000, 0x99000000}));
        root.addView(scrim, match());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(sideInset, dp(32), sideInset, dp(30));
        // Keep clipping on: the scroller relies on its parent to cut off rows below the safe area.

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        style(time, 56, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        header.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        style(date, 20, 0xD9FFFFFF, Typeface.DEFAULT);
        date.setPadding(dp(20), 0, 0, dp(12));
        header.addView(date);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(settingsButton());
        col.addView(header);

        caption = new TextView(this);
        style(caption, 24, Color.WHITE, Typeface.DEFAULT_BOLD);
        caption.setSingleLine(true);
        caption.setPadding(0, dp(4), 0, dp(6));
        col.addView(caption);

        TextView section = new TextView(this);
        style(section, 15, 0xB3FFFFFF, Typeface.DEFAULT_BOLD);
        section.setText(isMoreScreen() ? "MORE APPS" : "RECENTLY PLAYED");
        section.setLetterSpacing(0.12f);
        section.setPadding(0, 0, 0, dp(4));
        col.addView(section);

        grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setUseDefaultMargins(false);
        // Room for the focused tile's zoom so it is never clipped.
        grid.setPadding(gridPad, dp(12), gridPad, dp(12));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);

        // The scroller clips at its own bounds (inside the safe area), so lower rows peek
        // neatly instead of running off the screen; the grid's padding leaves room for the zoom.
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFadingEdgeLength(dp(24));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFocusable(false);
        scroll.setSmoothScrollingEnabled(true);
        scroll.addView(grid);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, match());
        return root;
    }

    private void render(boolean keepFocus) {
        View focused = getCurrentFocus();
        Object focusTag = keepFocus && focused != null ? focused.getTag() : null;

        grid.removeAllViews();
        shownOrder.clear();
        List<App> visible = visibleApps();
        for (App a : visible) {
            grid.addView(tile(a));
            shownOrder.add(a.pkg);
        }
        if (isMoreScreen()) {
            grid.addView(settingsTile());
        } else {
            int remaining = sortedApps().size() - visible.size();
            if (remaining > 0) grid.addView(moreTile(remaining));
        }

        View target = focusTag != null ? grid.findViewWithTag(focusTag) : null;
        if (target != null) target.requestFocus();
        else focusFirst();
    }

    private void focusFirst() {
        if (grid.getChildCount() == 0) return;
        scroll.scrollTo(0, 0);
        grid.getChildAt(0).requestFocus();
    }

    private View tile(final App app) {
        FrameLayout f = baseTile(app.pkg, app.label);
        if (app.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(app.banner);
            f.addView(iv, match());
        } else {
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(app.icon);
            inner.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView t = new TextView(this);
            style(t, 13, Color.WHITE, Typeface.DEFAULT);
            t.setText(app.label);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(4), dp(8), 0);
            inner.addView(t);
            f.addView(inner, match());
        }
        f.setOnClickListener(v -> open(app));
        f.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
        return f;
    }

    private View settingsTile() {
        FrameLayout f = baseTile(SETTINGS, "Settings");
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        TextView gear = new TextView(this);
        style(gear, 34, Color.WHITE, Typeface.DEFAULT);
        gear.setText("⚙");
        gear.setGravity(Gravity.CENTER);
        inner.addView(gear);
        TextView t = new TextView(this);
        style(t, 14, Color.WHITE, Typeface.DEFAULT_BOLD);
        t.setText("Settings");
        t.setGravity(Gravity.CENTER);
        inner.addView(t);
        f.addView(inner, match());
        f.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        f.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        return f;
    }

    private View moreTile(int count) {
        FrameLayout f = baseTile(MORE, "More Apps");
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        TextView dots = new TextView(this);
        style(dots, 40, Color.WHITE, Typeface.DEFAULT_BOLD);
        dots.setText("\u2022\u2022\u2022");
        dots.setGravity(Gravity.CENTER);
        inner.addView(dots);
        TextView t = new TextView(this);
        style(t, 20, Color.WHITE, Typeface.DEFAULT_BOLD);
        t.setText("More Apps");
        t.setGravity(Gravity.CENTER);
        inner.addView(t);
        TextView n = new TextView(this);
        style(n, 14, 0xB3FFFFFF, Typeface.DEFAULT);
        n.setText(count + (count == 1 ? " app" : " apps"));
        n.setGravity(Gravity.CENTER);
        inner.addView(n);
        f.addView(inner, match());
        f.setOnClickListener(v -> launch(new Intent(this, MoreAppsActivity.class)));
        return f;
    }

    /** Always-available way into Settings (top right), so you can never be locked out. */
    private View settingsButton() {
        final TextView b = new TextView(this);
        style(b, 18, Color.WHITE, Typeface.DEFAULT_BOLD);
        b.setText("\u2699  Settings");
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setFocusable(true);
        b.setClickable(true);
        b.setTag("__settings_button__");
        final GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(dp(22));
        idle.setColor(0x33FFFFFF);
        final GradientDrawable lit = new GradientDrawable();
        lit.setCornerRadius(dp(22));
        lit.setColor(0x33FFFFFF);
        lit.setStroke(dp(3), AMBER);
        b.setBackground(idle);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? lit : idle);
            if (has) caption.setText("Settings");
        });
        b.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        b.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    /** A focusable 16:9 card with an amber ring and a gentle zoom when focused. */
    private FrameLayout baseTile(String tag, final CharSequence label) {
        final FrameLayout f = new FrameLayout(this);
        f.setTag(tag);
        f.setFocusable(true);
        f.setClickable(true);
        f.setContentDescription(label);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = tileW;
        lp.height = tileH;
        int index = grid.getChildCount();
        lp.setMargins(index % COLUMNS == 0 ? 0 : gap, 0, 0, gap);
        f.setLayoutParams(lp);

        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(8));
        card.setColor(0xE61C2230);
        f.setBackground(card);
        f.setClipToOutline(true);

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(8));
        ring.setStroke(dp(4), AMBER);

        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .translationZ(has ? dp(10) : 0).setDuration(140).start();
            f.setForeground(has ? ring : null);
            if (has) caption.setText(label);
        });
        return f;
    }

    private void style(TextView t, int sp, int color, Typeface face) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- apps and ordering

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        new Thread(() -> {
            final Map<String, App> found = new LinkedHashMap<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
            collect(pm, Intent.CATEGORY_LAUNCHER, found);
            final Map<String, Long> usage = usageTimes();
            runOnUiThread(() -> {
                apps.clear();
                apps.putAll(found);
                applyLastUsed(usage);
                render(false);
            });
        }, "load-apps").start();
    }

    /** Re-sorts after returning from an app; only rebuilds the grid if the order changed. */
    private void refreshOrder(final boolean focusNewest) {
        new Thread(() -> {
            final Map<String, Long> usage = usageTimes();
            runOnUiThread(() -> {
                applyLastUsed(usage);
                List<String> order = new ArrayList<>();
                for (App a : visibleApps()) order.add(a.pkg);
                if (!order.equals(shownOrder)) render(!focusNewest);
                else if (focusNewest) focusFirst();
            });
        }, "refresh-order").start();
    }

    private void collect(PackageManager pm, String category, Map<String, App> found) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || HIDDEN_ALWAYS.contains(pkg) || found.containsKey(pkg)) {
                continue;
            }
            App a = new App();
            a.pkg = pkg;
            a.label = ri.loadLabel(pm);
            a.banner = ri.activityInfo.loadBanner(pm);
            if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (a.banner == null) a.icon = ri.loadIcon(pm);
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setClassName(pkg, ri.activityInfo.name);
            }
            a.launch = launch;
            found.put(pkg, a);
        }
    }

    /** Android's own last-used times. Empty unless Usage access is granted (optional). */
    private Map<String, Long> usageTimes() {
        Map<String, Long> out = new HashMap<>();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - USAGE_LOOKBACK_MS, now);
        } catch (RuntimeException e) {
            return out;
        }
        if (stats == null) return out;
        for (UsageStats s : stats) {
            // Only count real foreground use, not background services.
            if (s.getTotalTimeInForeground() <= 0) continue;
            Long prev = out.get(s.getPackageName());
            if (prev == null || s.getLastTimeUsed() > prev) out.put(s.getPackageName(), s.getLastTimeUsed());
        }
        return out;
    }

    private void applyLastUsed(Map<String, Long> usage) {
        for (App a : apps.values()) {
            long own = prefs.getLong(LAST_USED + a.pkg, 0);
            Long sys = usage.get(a.pkg);
            a.lastUsed = Math.max(own, sys != null ? sys : 0);
        }
    }

    /** Most recent first; never-used apps follow in seed order, then A–Z. Hidden apps left out. */
    private List<App> sortedApps() {
        Set<String> hidden = hiddenApps();
        List<App> list = new ArrayList<>();
        for (App a : apps.values()) if (!hidden.contains(a.pkg)) list.add(a);
        Collections.sort(list, (a, b) -> {
            if (a.lastUsed != b.lastUsed) return Long.compare(b.lastUsed, a.lastUsed);
            int sa = seedRank(a.pkg);
            int sb = seedRank(b.pkg);
            if (sa != sb) return Integer.compare(sa, sb);
            return a.label.toString().compareToIgnoreCase(b.label.toString());
        });
        return list;
    }

    /** Home: the 7 most recent. More Apps: everything else, A–Z. */
    private List<App> visibleApps() {
        List<App> sorted = sortedApps();
        int split = Math.min(RECENT_COUNT, sorted.size());
        if (!isMoreScreen()) return new ArrayList<>(sorted.subList(0, split));
        List<App> rest = new ArrayList<>(sorted.subList(split, sorted.size()));
        Collections.sort(rest, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        return rest;
    }

    private static int seedRank(String pkg) {
        int i = SEED_ORDER.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private void open(App app) {
        prefs.edit().putLong(LAST_USED + app.pkg, System.currentTimeMillis()).apply();
        launch(app.launch);
        // Coming back from the app should land on Home, where it now sits in Recently played.
        if (isMoreScreen()) finish();
    }

    private void launch(Intent intent) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't open that app", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- hide / unhide

    private Set<String> hiddenApps() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.<String>emptySet()));
    }

    private void showAppMenu(final App app) {
        CharSequence[] items = {"Hide from home", "App info"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Set<String> hidden = hiddenApps();
                        hidden.add(app.pkg);
                        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
                        render(false);
                    } else {
                        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", app.pkg, null)));
                    }
                })
                .show();
    }

    private void showSettingsMenu() {
        final boolean motion = prefs.getBoolean(KEY_MOTION, true);
        final int n = hiddenApps().size();
        List<CharSequence> items = new ArrayList<>();
        items.add(motion ? "Background motion: On (turn off)" : "Background motion: Off (turn on)");
        if (n > 0) items.add("Show all hidden apps (" + n + ")");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Hush")
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (which == 0) {
                        prefs.edit().putBoolean(KEY_MOTION, !motion).apply();
                        updateMotion();
                    } else {
                        prefs.edit().remove(KEY_HIDDEN).apply();
                        render(false);
                    }
                })
                .show();
    }
}
