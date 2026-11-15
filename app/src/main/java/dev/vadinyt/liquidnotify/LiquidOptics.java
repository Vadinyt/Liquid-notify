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
        final Paint glaze = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint leftGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rightGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shimmer = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = -1f;
        int intensity = -1;
        long startTime = -1L;

        State() {
            glaze.setStyle(Paint.Style.FILL);
            rim.setStyle(Paint.Style.STROKE);
            leftGlow.setStyle(Paint.Style.FILL);
            rightGlow.setStyle(Paint.Style.FILL);
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
                     int strength, int opacity) {
        if (canvas == null || rect == null || rect.isEmpty()
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
                || st.intensity != strength;
        if (changed) {
            st.bounds.set(rect);
            st.radius = rad;
            st.intensity = strength;
            st.startTime = SystemClock.uptimeMillis();

            st.clip.reset();
            st.clip.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
            // Transparent ice-blue surface: the actual color of the pane
            // comes from this fill, rather than Samsung's dark gray drawable.
            final int topTint = 0x9CF6FCFF;
            final int midTint = 0x77D8F0FF;
            final int baseTint = 0x62C6E5F9;
            st.glaze.setShader(new LinearGradient(l, t, l, b,
                    new int[]{topTint, midTint, 0x6DD1EBFF, baseTint},
                    new float[]{0f, .30f, .72f, 1f},
                    Shader.TileMode.CLAMP));
            st.rim.setShader(new LinearGradient(l, t, r, b,
                    new int[]{0x96FFFFFF, 0x64E4F5FF, 0x09FFFFFF, 0x45F2F8FF},
                    new float[]{0f, .25f, .72f, 1f}, Shader.TileMode.CLAMP));
            st.leftGlow.setShader(new RadialGradient(
                    l + rad * .72f, t + rad * .7f,
                    Math.max(rad * 1.45f, width * .23f),
                    new int[]{0x3ADAF9FF, 0x0BD5F5FF, 0x00DAF9FF},
                    new float[]{0f, .42f, 1f}, Shader.TileMode.CLAMP));
            final int[] rightSpectrum =
                    new int[]{0x22CCEEFF, 0x05CCEEFF, 0x00CCEEFF};
            st.rightGlow.setShader(new RadialGradient(
                    r - rad * .68f, b - rad,
                    Math.max(rad * 1.4f, width * .25f),
                    rightSpectrum,
                    new float[]{0f, .40f, 1f}, Shader.TileMode.CLAMP));

        }

        final int save = canvas.save();
        canvas.clipPath(st.clip);
        // A faint *bright* material glaze, rather than an opaque gray panel.
        // A continuous glass pane, controlled independently by the opacity
        // slider. At the default 76/255 it is visibly light but still shows
        // the shade behind; higher values intentionally make it more opaque.
        st.glaze.setAlpha(Math.max(32, Math.min(230,
                Math.round(20f + opacity * .80f))));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.glaze);

        // Reflections may be disabled without removing the glass surface.
        if (strength <= 0) {
            canvas.restoreToCount(save);
            return;
        }
        // Two soft halos establish depth but don't hide the wallpaper.
        st.leftGlow.setAlpha(Math.round(190f * power));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.leftGlow);
        st.rightGlow.setAlpha(Math.round(150f * power));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.rightGlow);

        // A thin bright outside rim, inset so it cannot spill or form crescents.
        st.rim.setStrokeWidth(Math.max(.5f, .66f * density));
        st.rim.setAlpha(Math.round(176f * power));
        canvas.drawRoundRect(l, t, r, b, rad, rad, st.rim);

        // Single edge only. Earlier double rim and free-form corner arcs
        // generated the metallic double border and stray corner scratches.
        // The top-side light comes from the rim shader, not an extra stroke.

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
