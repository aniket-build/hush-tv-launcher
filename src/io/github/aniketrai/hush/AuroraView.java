package io.github.aniketrai.hush;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;

/**
 * A slowly drifting "aurora" of soft colour blobs, tinted by time of day.
 *
 * Built for a weak TV chip: each frame is drawn into a tiny 160×90 bitmap (~14k pixels) and
 * stretched to full screen with bilinear filtering, which also gives the soft, blurred look.
 * Runs at ~15 fps and only while visible; when stopped it keeps showing the last frame.
 */
public class AuroraView extends View {
    private static final int W = 160;
    private static final int H = 90;
    private static final long FRAME_MS = 66;

    /** {base, blob1, blob2, blob3, blob4} per part of the day. */
    private static final int[][] PALETTES = {
            // night 0–5: deep indigo, teal, violet
            {0xFF0B1024, 0xFF2B2F8F, 0xFF0F6E7A, 0xFF5B2A86, 0xFF1C4E9A},
            // morning 6–11: dusk peach, rose, sky
            {0xFF1A1F3A, 0xFFE07A5F, 0xFFB5527A, 0xFF3D7CC9, 0xFFE3A857},
            // afternoon 12–17: aqua, teal, indigo
            {0xFF0E1E2E, 0xFF1FA2A6, 0xFF2D6CDF, 0xFF15806B, 0xFF6A5ACD},
            // evening 18–23: violet, magenta, blue
            {0xFF120E26, 0xFF7B3FD1, 0xFFC2457A, 0xFF2A5BD7, 0xFF8E2DA8},
    };

    private final Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas frameCanvas = new Canvas(frame);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect dst = new Rect();
    private final long start = SystemClock.uptimeMillis();
    private boolean running;
    private int[] palette = PALETTES[0];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            renderFrame();
            invalidate();
            postOnAnimationDelayed(this, FRAME_MS);
        }
    };

    public AuroraView(Context context) {
        super(context);
        renderFrame();
    }

    /** Start or stop the drift. Stopped = a still gradient (the last frame). */
    public void setRunning(boolean run) {
        if (run == running) return;
        running = run;
        removeCallbacks(tick);
        if (run) post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        setRunning(false);
        super.onDetachedFromWindow();
    }

    private void renderFrame() {
        palette = PALETTES[Calendar.getInstance().get(Calendar.HOUR_OF_DAY) / 6];
        // Seconds since start; each blob follows its own slow Lissajous path (~40–90 s loops).
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        frameCanvas.drawColor(palette[0]);
        blob(1, 0.25f + 0.20f * sin(t / 13f), 0.30f + 0.25f * sin(t / 17f + 1f), 0.75f, t);
        blob(2, 0.75f + 0.20f * sin(t / 15f + 2f), 0.35f + 0.25f * sin(t / 11f + 3f), 0.70f, t);
        blob(3, 0.55f + 0.30f * sin(t / 19f + 4f), 0.80f + 0.15f * sin(t / 14f + 5f), 0.80f, t);
        blob(4, 0.15f + 0.20f * sin(t / 21f + 6f), 0.85f + 0.15f * sin(t / 9f + 2f), 0.60f, t);
    }

    private void blob(int i, float cx, float cy, float radius, float t) {
        float r = W * radius * (0.9f + 0.1f * sin(t / 7f + i));
        int c = palette[i];
        // Fade each blob to transparent so they blend into soft colour fields.
        int core = Color.argb(150, Color.red(c), Color.green(c), Color.blue(c));
        int edge = Color.argb(0, Color.red(c), Color.green(c), Color.blue(c));
        blobPaint.setShader(new RadialGradient(cx * W, cy * H, r, core, edge, Shader.TileMode.CLAMP));
        frameCanvas.drawCircle(cx * W, cy * H, r, blobPaint);
    }

    private static float sin(float x) {
        return (float) Math.sin(x);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(frame, null, dst, scalePaint);
    }
}
