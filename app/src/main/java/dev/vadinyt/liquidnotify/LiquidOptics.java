package dev.vadinyt.liquidnotify;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.WeakHashMap;

/**
 * Lightweight optical imitation of liquid glass on a native Samsung
 * NotificationBackgroundView. It does not sample or distort pixels behind
 * the card: real lens refraction would require an OEM framebuffer shader.
 *
 * Light moves briefly on the first display or after a setting change.
 * The animation is finite, respects Android's animator duration setting,
 * and uses no separate view, overlay, or new window.
 */
final class LiquidOptics {
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();
    private static final long ANIM_MS = 720L;

    private static final class State {
        final Path clip = new Path();
        final Path upperArc = new Path();
        final Path lowerArc = new Path();
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint innerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint cyan = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint violet = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect rect = new Rect();
        float radius = -1;
        int intensity = -1;
        int palette = -1;
        long started = -1;
        float previousY = Float.NaN;

        State() {
            rim.setStyle(Paint.Style.STROKE);
            innerRim.setStyle(Paint.Style.STROKE);
            streak.setStyle(Paint.Style.STROKE);
            streak.setStrokeCap(Paint.Cap.ROUND);
        }
    }

    private static State state(View view) {
        State value = STATES.get(view);
        if (value == null) {
            value = new State();
            STATES.put(view, value);
        }
        return value;
    }

    static void draw(View view, Canvas canvas, Rect bounds, float requestedRadius,
                     int intensity, int opacity, int palette) {
        if (canvas == null || intensity <= 0 || bounds == null || bounds.isEmpty()
                || view.getVisibility() != View.VISIBLE) return;
        if (bounds.width() < 96 || bounds.height() < 48) return;

        final float density = view.getResources().getDisplayMetrics().density;
        final float inset = Math.max(1f, density * 1.5f);
        final float left = bounds.left + inset;
        final float top = bounds.top + inset;
        final float right = bounds.right - inset;
        final float bottom = bounds.bottom - inset;
        final float w = right - left;
        final float h = bottom - top;
        if (w <= 12f || h <= 12f) return;
        final float radius = Math.max(4f, Math.min(requestedRadius, Math.min(w, h) * .5f));
        final float power = Math.min(100, Math.max(0, intensity)) / 100f;
        final State st = state(view);

        boolean rebuilt = !st.rect.equals(bounds)
                || Math.abs(st.radius - radius) > .5f
                || st.palette != palette || st.intensity != intensity;
        if (rebuilt) {
            st.rect.set(bounds);
            st.radius = radius;
            st.intensity = intensity;
            st.palette = palette;
            st.clip.reset();
            st.clip.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
            st.upperArc.reset();
            st.upperArc.moveTo(left + radius * .18f, top + radius * .62f);
            st.upperArc.quadTo(left + radius * .12f, top + inset, left + radius, top);
            st.upperArc.lineTo(right - radius * .85f, top);
            st.lowerArc.reset();
            st.lowerArc.moveTo(left + radius * .58f, bottom);
            st.lowerArc.lineTo(right - radius, bottom);
            st.lowerArc.quadTo(right - radius * .1f, bottom, right, bottom - radius);
            st.started = SystemClock.uptimeMillis();
        }

        final int save = canvas.save();
        canvas.clipPath(st.clip);

        // Thin spectral lens edges: bright rim, secondary inner reflection,
        // opposite cyan/violet light spill. This simulates optical thickness.
        st.rim.setStrokeWidth(Math.max(.7f, density * 1.05f));
        st.rim.setShader(new LinearGradient(
                left, top, right, bottom,
                new int[]{0xB7FFFFFF, 0x38CDEEFF, 0x16FFFFFF, 0x72FFFFFF},
                new float[]{0f, .30f, .72f, 1f}, Shader.TileMode.CLAMP));
        st.rim.setAlpha(Math.round(230f * power));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, st.rim);

        st.innerRim.setStrokeWidth(Math.max(.5f, density * .75f));
        st.innerRim.setShader(new LinearGradient(
                left + inset, top + inset, right - inset, bottom - inset,
                new int[]{0x5AFFFFFF, 0x06FFFFFF, 0x48FFFFFF},
                new float[]{0f, .50f, 1f}, Shader.TileMode.CLAMP));
        st.innerRim.setAlpha(Math.round(175f * power));
        canvas.drawRoundRect(left + inset * .8f, top + inset * .8f,
                right - inset * .8f, bottom - inset * .8f,
                Math.max(0f, radius - inset * .8f),
                Math.max(0f, radius - inset * .8f), st.innerRim);

        st.cyan.setStyle(Paint.Style.FILL);
        st.cyan.setShader(new RadialGradient(left + radius * .7f, top + radius,
                Math.max(radius * 2f, w * .28f),
                new int[]{0x3D91E9FF, 0x0991E9FF, 0x0091E9FF},
                new float[]{0f, .40f, 1f}, Shader.TileMode.CLAMP));
        st.cyan.setAlpha(Math.round(155f * power));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, st.cyan);

        st.violet.setShader(new RadialGradient(right - radius * .6f, bottom - radius,
                Math.max(radius * 2f, w * .31f),
                new int[]{0x30E1A5FF, 0x08E1A5FF, 0x00E1A5FF},
                new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP));
        st.violet.setAlpha(Math.round(150f * power));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, st.violet);

        // A curved specular streak is the primary liquid-glass cue. It is
        // inside the native card and cannot spill over the row's boundaries.
        st.streak.setStrokeWidth(Math.max(.9f, density * 1.15f));
        st.streak.setShader(new LinearGradient(left, top, right, top,
                new int[]{0xD9FFFFFF, 0x5DFFFFFF, 0x0AFFFFFF},
                null, Shader.TileMode.CLAMP));
        st.streak.setAlpha(Math.round(220f * power));
        canvas.drawPath(st.upperArc, st.streak);
        st.streak.setAlpha(Math.round(74f * power));
        canvas.drawPath(st.lowerArc, st.streak);

        // Very short, finite shimmer when a card first appears or changes.
        // Avoid permanent 60fps animation in SystemUI and respect reduce motion.
        final long elapsed = SystemClock.uptimeMillis() - st.started;
        float animScale = 1f;
        try {
            animScale = android.provider.Settings.Global.getFloat(
                    view.getContext().getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {}
        if (animScale > 0f && elapsed >= 0L && elapsed < ANIM_MS
                && view.isShown() && view.isAttachedToWindow()) {
            float t = Math.min(1f, elapsed / (float) ANIM_MS);
            float ease = t * t * (3f - 2f * t);
            float center = left + w * (.12f + .76f * ease);
            st.glint.setShader(new RadialGradient(center, top + radius * .55f,
                    Math.max(density * 35f, w * .22f),
                    new int[]{0x62FFFFFF, 0x15FFFFFF, 0x00FFFFFF},
                    new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP));
            st.glint.setAlpha(Math.round(140f * power * (1f - t)));
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, st.glint);
            view.postInvalidateDelayed(48L);
        }
        canvas.restoreToCount(save);
    }

    private LiquidOptics() {}
}
