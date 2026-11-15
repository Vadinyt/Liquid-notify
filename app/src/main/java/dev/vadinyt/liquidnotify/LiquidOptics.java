package dev.vadinyt.liquidnotify;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import java.util.WeakHashMap;

/**
 * iOS-like clear liquid-glass optics on Samsung's native notification card.
 *
 * Creates a pale translucent upper glaze, two internally clipped lens rims,
 * chromatic edge glow, and a brief traveling reflection. This is an optical
 * approximation; wallpaper pixel refraction is NOT implemented.
 *
 * No background-capture API, overlay window, notification replacement, or
 * separate render view. The shimmer is finite and respects reduced motion.
 */
final class LiquidOptics {
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();
    private static final long SHIMMER_MS = 620L;

    private static final class State {
        final Rect bounds = new Rect();
        final Path clip = new Path();
        final Path topCurve = new Path();
        final Path bottomCurve = new Path();
        final Paint glaze = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint leftGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rightGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shimmer = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = -1f;
        int palette = -1;
        int intensity = -1;
        long startTime = -1L;

        State() {
            glaze.setStyle(Paint.Style.FILL);
            rim.setStyle(Paint.Style.STROKE);
            inner.setStyle(Paint.Style.STROKE);
            leftGlow.setStyle(Paint.Style.FILL);
            rightGlow.setStyle(Paint.Style.FILL);
            curve.setStyle(Paint.Style.STROKE);
            curve.setStrokeCap(Paint.Cap.ROUND);
            shimmer.setStyle(Paint.Style.FILL);
        }
    }

    private static State state(View view) {
        State s = STATES.get(view);
        if (s == null) {
            s = new State();
            STATES.put(view, s);
        }
        return s;
    }

    static void draw(View view, Canvas canvas, Rect rect, float radiusPx,
                     int strength, int opacity, int palette) {
        if (canvas == null || rect == null || rect.isEmpty() || strength <= 0
                || view.getVisibility() != View.VISIBLE
                || rect.width() < 96 || rect.height() < 48) return;

        final float density = view.getResources().getDisplayMetrics().density;
        final float inset = Math.max(1f, density * 1.15f);
        final float l = rect.left + inset, t = rect.top + inset;
        final float r = rect.right - inset, b = rect.bottom - inset;
        final float width = r - l, height = b - t;
        if (width < 20f || height < 20f) return;
        final float rad = Math.max(3f,
                Math.min(radiusPx - inset, Math.min(width, height) / 2f));
        final float power = Math.min(100f, strength) / 100f;
        final State st = state(view);

        final boolean changed = !st.bounds.equals(rect)
                || Math.abs(st.radius - rad) > .5f
                || st.palette != palette || st.intensity != strength;
        if (changed) {
            st.bounds.set(rect);
            st.radius = rad;
            st.palette = palette;
            st.intensity = strength;
            st.startTime = SystemClock.uptimeMillis();

            st.clip.reset();
            st.clip.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
            st.topCurve.reset();
            st.topCurve.moveTo(l + rad * .16f, t + rad * .65f);
            st.topCurve.cubicTo(l + rad * .17f, t + rad * .08f,
                    l + rad * .48f, t, l + rad, t);
            st.topCurve.lineTo(r - rad * .7f, t);
            st.bottomCurve.reset();
            st.bottomCurve.moveTo(l + rad * .55f, b);
            st.bottomCurve.lineTo(r - rad, b);
            st.bottomCurve.quadTo(r - rad * .13f, b, r, b - rad);

            final int topTint, midTint, baseTint;
            switch (palette) {
                case 1: // pearl graphite
                    topTint = 0x4FE5EEF7;
                    midTint = 0x17CAD8E8;
                    baseTint = 0x1BD1E0EE;
                    break;
                case 2: // pastel lavender
                    topTint = 0x58F4E5FF;
                    midTint = 0x13DCD1F3;
                    baseTint = 0x19E8D8FF;
                    break;
                default: // pale blue-clear lens
                    topTint = 0x5DF0F9FF;
                    midTint = 0x1AD0ECFF;
                    baseTint = 0x20DDF5FF;
                    break;
            }
            st.glaze.setShader(new LinearGradient(l, t, l, b,
                    new int[]{topTint, midTint, 0x04FFFFFF, baseTint},
                    new float[]{0f, .28f, .78f, 1f},
                    Shader.TileMode.CLAMP));
            st.rim.setShader(new LinearGradient(l, t, r, b,
                    new int[]{0xD1FFFFFF, 0x64CDEAFF, 0x20FFFFFF, 0xA4EDF8FF},
                    new float[]{0f, .28f, .72f, 1f}, Shader.TileMode.CLAMP));
            st.inner.setShader(new LinearGradient(l, t, r, b,
                    new int[]{0x69FFFFFF, 0x0DFFFFFF, 0x4DFFFFFF},
                    new float[]{0f, .56f, 1f}, Shader.TileMode.CLAMP));
            st.leftGlow.setShader(new RadialGradient(
                    l + rad * .72f, t + rad * .7f,
                    Math.max(rad * 1.45f, width * .23f),
                    new int[]{0x3ADAF9FF, 0x0BD5F5FF, 0x00DAF9FF},
                    new float[]{0f, .42f, 1f}, Shader.TileMode.CLAMP));
            final int[] rightSpectrum = palette == 2
                    ? new int[]{0x2BD7B0FD, 0x06D7B0FD, 0x00D7B0FD}
                    : new int[]{0x2AC6E6FF, 0x07C6E6FF, 0x00C6E6FF};
            st.rightGlow.setShader(new RadialGradient(
                    r - rad * .68f, b - rad,
                    Math.max(rad * 1.4f, width * .25f),
                    rightSpectrum,
                    new float[]{0f, .40f, 1f}, Shader.TileMode.CLAMP));
            st.curve.setShader(new LinearGradient(l, t, r, t,
                    new int[]{0xE1FFFFFF, 0xA3FFFFFF, 0x00FFFFFF},
                    new float[]{0f, .38f, 1f}, Shader.TileMode.CLAMP));
        }

        final int save = canvas.save();
        canvas.clipPath(st.clip);
        // A faint *bright* material glaze, rather than an opaque gray panel.
        st.glaze.setAlpha(Math.round(Math.min(255f, (60f + opacity * .64f) * power)));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.glaze);

        // Two soft halos establish depth but don't hide the wallpaper.
        st.leftGlow.setAlpha(Math.round(190f * power));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.leftGlow);
        st.rightGlow.setAlpha(Math.round(150f * power));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.rightGlow);

        // A thin bright outside rim, inset so it cannot spill or form crescents.
        st.rim.setStrokeWidth(Math.max(.65f, .90f * density));
        st.rim.setAlpha(Math.round(216f * power));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.rim);

        final float offset = Math.max(.6f, density * 1.2f);
        final float innerRadius = Math.max(0f, rad - offset);
        st.inner.setStrokeWidth(Math.max(.4f, density * .55f));
        st.inner.setAlpha(Math.round(146f * power));
        canvas.drawRoundRect(l + offset, t + offset, r - offset, b - offset,
                innerRadius, innerRadius, st.inner);

        st.curve.setStrokeWidth(Math.max(.9f, density * 1.12f));
        st.curve.setAlpha(Math.round(196f * power));
        canvas.drawPath(st.topCurve, st.curve);
        st.curve.setAlpha(Math.round(70f * power));
        canvas.drawPath(st.bottomCurve, st.curve);

        long elapsed = SystemClock.uptimeMillis() - st.startTime;
        float animatorScale = 1f;
        try {
            animatorScale = Settings.Global.getFloat(view.getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {}
        if (animatorScale > 0f && elapsed >= 0L && elapsed < SHIMMER_MS
                && view.isAttachedToWindow() && view.isShown()) {
            final float x = elapsed / (float) SHIMMER_MS;
            final float eased = x * x * (3f - 2f * x);
            final float centerX = l + width * (.10f + .80f * eased);
            // The traveling light is subtle: a highlight passing through glass,
            // not a full-card white flash.
            st.shimmer.setShader(new RadialGradient(
                    centerX, t + rad * .50f,
                    Math.max(density * 30f, width * .18f),
                    new int[]{0x5CFFFFFF, 0x0CFFFFFF, 0x00FFFFFF},
                    new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP));
            st.shimmer.setAlpha(Math.round(126f * power * (1f - x)));
            canvas.drawRoundRect(l, t, r, b, rad, rad, st.shimmer);
            view.postInvalidateDelayed(60L);
        }
        canvas.restoreToCount(save);
    }

    private LiquidOptics() {}
}
