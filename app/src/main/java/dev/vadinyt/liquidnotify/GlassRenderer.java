package dev.vadinyt.liquidnotify;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Draws a restrained translucent base directly on Samsung's notification background view.
 * There is no foreground overlay, replacement content view, or new window.
 *
 * Samsung SemBlurInfo is an OEM API, so it is resolved and invoked reflectively.
 * If not available on the installed firmware, native notifications still draw.
 */
final class GlassRenderer {
    private static final String TAG = "LiquidNotify";
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();
    private static Field backgroundField;
    private static Field originalAlphaField;
    private static Field clipTopField;
    private static Field clipBottomField;
    private static Field actualHeightField;
    private static Field expandingField;
    private static boolean fieldsResolved;
    private static volatile int blurApiState; // 0 unknown, 1 available, -1 missing
    private static Constructor<?> blurBuilder;
    private static Method setRadius;
    private static Method setColor;
    private static Method setCorners;
    private static Method build;
    private static Method apply;
    private static boolean loggedNative;
    private static boolean loggedMissing;

    private static final class State {
        boolean blurApplied;
        boolean blurFailed;
        int layerSaveCount = -1;
        Canvas savedLayerCanvas;
        int blurColor = Integer.MIN_VALUE;
        // Tracks whether an OEM background occupies the complete measured view.
        // Samsung clips/animates notification heights independently from layouts.
        boolean geometryEligible;
        int blurRadius = -1;
        float cornerRadius = -1f;
        int lastAlpha = -1;
        int lastRectW = -1;
        int lastRectH = -1;
        int lastRectLeft = Integer.MIN_VALUE;
        int lastRectTop = Integer.MIN_VALUE;
        float lastCorner = -1f;
        int lastPalette = -1;
        boolean lastDimmed;
        final Paint paletteFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path shape = new Path();

        State() {
            paletteFill.setStyle(Paint.Style.FILL);
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

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> type = clazz; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable error) {
                break;
            }
        }
        return null;
    }

    private static void ensureFields(View view) {
        if (fieldsResolved) return;
        backgroundField = findField(view.getClass(), "mBackground");
        originalAlphaField = findField(view.getClass(), "mDrawableAlpha");
        clipTopField = findField(view.getClass(), "mClipTopAmount");
        clipBottomField = findField(view.getClass(), "mClipBottomAmount");
        actualHeightField = findField(view.getClass(), "mActualHeight");
        expandingField = findField(view.getClass(), "mExpandAnimationRunning");
        fieldsResolved = true;
        if (backgroundField == null && !loggedMissing) {
            loggedMissing = true;
            XposedBridge.log(TAG + ": mBackground field unavailable for glass renderer");
        }
    }

    private static Drawable background(View view) {
        ensureFields(view);
        if (backgroundField == null) return null;
        try {
            Object value = backgroundField.get(view);
            return value instanceof Drawable ? (Drawable) value : null;
        } catch (Throwable e) {
            return null;
        }
    }

    static boolean hasNativeBackground(View view) {
        return background(view) != null;
    }

    static void beforeDraw(View view, Canvas canvas, boolean active, int opacity,
                           int blurRadiusPx, float cornerRadiusPx, int palette) {
        State value = state(view);
        Drawable background = background(view);

        // Reset alpha when switched off. Do not modify the notification text/icons.
        if (!active) {
            if (background != null && value.lastAlpha != -1) {
                background.setAlpha(originalAlpha(view));
                value.lastAlpha = -1;
            }
            clearBlur(view, value);
            return;
        }

        // Draw Samsung's entire background into one opacity-controlled layer.
        // The previous mBackground.setAlpha() did not consistently control
        // Samsung's internal drawable/layer compositing on One UI 9.
        // This layer affects only NotificationBackgroundView.onDraw(), never
        // the notification's text, icons or touch surfaces.
        if (canvas != null && view.getWidth() > 0 && view.getHeight() > 0
                && value.layerSaveCount == -1) {
            final int alpha = Math.max(35, Math.min(245, opacity));
            value.layerSaveCount = canvas.saveLayerAlpha(
                    0f, 0f, view.getWidth(), view.getHeight(), alpha);
            value.savedLayerCanvas = canvas;
        }

        blurRadiusPx = Math.max(0, Math.min(80, blurRadiusPx));
        int nativeTint = glassColor(palette, Math.round(opacity * 0.36f));
        // SemBlurInfo blurs the whole render node, while Samsung can draw a
        // smaller card inside it. Those bounds diverge during expansion and
        // shade transitions, producing square cutoffs/ghost crescents.
        // Native blur is deliberately not applied when bounds differ.
        boolean eligible = eligibleForBlur(view, background);
        if (blurRadiusPx <= 0 || !eligible) {
            clearBlur(view, value);
        } else if (!value.blurFailed && view.isAttachedToWindow()
                && view.getWidth() > 0 && view.getHeight() > 0
                && (!value.blurApplied || value.blurRadius != blurRadiusPx
                || value.cornerRadius != cornerRadiusPx
                || value.blurColor != nativeTint)) {
            if (ensureBlurApi()) {
                try {
                    Object builder = blurBuilder.newInstance(0); // Samsung MODE_WINDOW
                    setRadius.invoke(builder, blurRadiusPx);
                    setColor.invoke(builder, nativeTint);
                    setCorners.invoke(builder, cornerRadiusPx, cornerRadiusPx,
                            cornerRadiusPx, cornerRadiusPx);
                    apply.invoke(view, build.invoke(builder));
                    value.blurApplied = true;
                    value.blurRadius = blurRadiusPx;
                    value.cornerRadius = cornerRadiusPx;
                    value.blurColor = nativeTint;
                    if (!loggedNative) {
                        loggedNative = true;
                        XposedBridge.log(TAG + ": Samsung SemBlurInfo native card blur applied"
                                + " radiusPx=" + blurRadiusPx);
                    }
                } catch (Throwable error) {
                    value.blurFailed = true;
                    clearBlur(view, value);
                    if (!loggedMissing) {
                        loggedMissing = true;
                        XposedBridge.log(TAG + ": Samsung native card blur failed " + error);
                    }
                }
            }
        }
    }

    private static int originalAlpha(View view) {
        if (originalAlphaField != null) {
            try {
                return Math.max(0, Math.min(255, originalAlphaField.getInt(view)));
            } catch (Throwable ignored) {
            }
        }
        return 255;
    }

    static void afterDraw(View view, Canvas canvas, boolean active,
                          float cornerRadiusPx, int palette, boolean dimmed,
                          int opacity, int liquidStrength) {
        State drawState = state(view);
        // Restore the opacity-controlled layer *before* drawing the subtle
        // color wash and sheen. Everything drawn inside onDraw by Samsung is
        // now actually affected by the slider, including nested drawables.
        if (drawState.layerSaveCount != -1 && drawState.savedLayerCanvas == canvas) {
            try {
                canvas.restoreToCount(drawState.layerSaveCount);
            } finally {
                drawState.layerSaveCount = -1;
                drawState.savedLayerCanvas = null;
            }
        }
        if (!active) return;
        Drawable drawable = background(view);
        if (drawable == null) return;
        Rect bounds = drawable.getBounds();
        if (bounds.width() < 12 || bounds.height() < 12) return;
        State value = state(view);
        boolean eligible = eligibleForBlur(view, drawable);
        if (eligible != value.geometryEligible) {
            value.geometryEligible = eligible;
            // The initial frame may set drawable bounds only *inside*
            // NotificationBackgroundView.onDraw(). Revisit once afterward.
            view.postInvalidate();
        }
        // Refraction-like highlights can use the clipped drawable region even
        // when Samsung native blur cannot safely match this View's geometry.
        // Don't draw optical glints while the OEM expand animation is running.
        if (!eligible && readBoolField(view, expandingField)) return;
        if (bounds.left < -2 || bounds.top < -2
                || bounds.right > view.getWidth() + 2
                || bounds.bottom > view.getHeight() + 2) return;
        float edge = Math.max(1f, view.getResources().getDisplayMetrics().density * 0.9f);
        float left = bounds.left + edge;
        float top = bounds.top + edge;
        float right = bounds.right - edge;
        float bottom = bounds.bottom - edge;
        if (right <= left || bottom <= top) return;
        float corner = Math.min(cornerRadiusPx, Math.min(right - left, bottom - top) / 2f);

        // The old broad white stroke was added over Samsung's own rounded
        // blur surface, creating a doubled outline. Cache a true rounded clip
        // inside the actual native background bounds and omit the extra outline
        // whenever Samsung native blur is running.
        if (value.lastRectW != bounds.width() || value.lastRectH != bounds.height()
                || value.lastRectLeft != bounds.left || value.lastRectTop != bounds.top
                || value.lastCorner != corner || value.lastPalette != palette
                || value.lastDimmed != dimmed) {
            value.lastRectW = bounds.width();
            value.lastRectH = bounds.height();
            value.lastRectLeft = bounds.left;
            value.lastRectTop = bounds.top;
            value.lastCorner = corner;
            value.lastPalette = palette;
            value.lastDimmed = dimmed;
            value.shape.reset();
            value.shape.addRoundRect(left, top, right, bottom, corner, corner,
                    Path.Direction.CW);
            // Guaranteed live palette at every native background draw, even
            // when Samsung resets setTint during row rebinds/transitions.
            // It is an in-bounds translucent color wash, not a separate View.
            int topTint;
            int bottomTint;
            switch (palette) {
                case 1: // graphite
                    topTint = dimmed ? 0x26C3CFDB : 0x7CDDE6F1;
                    bottomTint = dimmed ? 0x16ADB9C9 : 0x39BCCAD9;
                    break;
                case 2: // violet
                    topTint = dimmed ? 0x24D8BBF3 : 0x7DE6CEFA;
                    bottomTint = dimmed ? 0x16BEA4E2 : 0x3EBEABEF;
                    break;
                default: // icy blue
                    topTint = dimmed ? 0x26B8E0FF : 0x88DEEEFF;
                    bottomTint = dimmed ? 0x1688BCE8 : 0x43A9D8FF;
                    break;
            }
            value.paletteFill.setShader(new LinearGradient(
                    left, top, right, bottom,
                    topTint, bottomTint, Shader.TileMode.CLAMP));
        }
        // Retain only a subtle translucent base color. LiquidOptics draws
        // beveled lens edges, spectral highlights and finite moving glints.
        // Unlike a permanent frosted overlay, these effects establish depth
        // without covering Samsung's notification content.
        value.paletteFill.setAlpha(dimmed ? Math.round(opacity * .30f)
                : Math.round(opacity * .85f));
        int save = canvas.save();
        canvas.clipPath(value.shape);
        canvas.drawRoundRect(left, top, right, bottom, corner, corner,
                value.paletteFill);
        canvas.restoreToCount(save);
        if (!dimmed && liquidStrength > 0) {
            LiquidOptics.draw(view, canvas, bounds, cornerRadiusPx,
                    liquidStrength, opacity, palette);
        }
    }

    private static int readIntField(View view, Field field, int fallback) {
        if (field == null) return fallback;
        try { return field.getInt(view); }
        catch (Throwable ignored) { return fallback; }
    }

    private static boolean readBoolField(View view, Field field) {
        if (field == null) return false;
        try { return field.getBoolean(view); }
        catch (Throwable ignored) { return false; }
    }

    /**
     * Unlike the View's dimensions, the OEM drawable bounds change with
     * mActualHeight/mActualWidth and clipping during row expansion.
     * Do not draw an independent window blur over mismatched geometries.
     */
    private static boolean eligibleForBlur(View view, Drawable drawable) {
        if (drawable == null || !view.isAttachedToWindow()) return false;
        int width = view.getWidth(), height = view.getHeight();
        if (width < 20 || height < 20) return false;
        if (readBoolField(view, expandingField)) return false;
        if (readIntField(view, clipTopField, 0) != 0
                || readIntField(view, clipBottomField, 0) != 0) return false;
        int actualHeight = readIntField(view, actualHeightField, height);
        if (actualHeight > 0 && Math.abs(actualHeight - height) > 2) return false;
        Rect bounds = drawable.getBounds();
        return !bounds.isEmpty()
                && Math.abs(bounds.left) <= 2
                && Math.abs(bounds.top) <= 2
                && Math.abs(bounds.right - width) <= 2
                && Math.abs(bounds.bottom - height) <= 2;
    }

    private static int glassColor(int palette, int alpha) {
        int red, green, blue;
        switch (palette) {
            case 1: red = 205; green = 215; blue = 229; break;
            case 2: red = 221; green = 190; blue = 242; break;
            default: red = 204; green = 230; blue = 252; break;
        }
        return Color.argb(Math.max(0, Math.min(255, alpha)), red, green, blue);
    }

    private static synchronized boolean ensureBlurApi() {
        if (blurApiState != 0) return blurApiState == 1;
        if (!"samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            blurApiState = -1;
            return false;
        }
        try {
            Class<?> blurClass = Class.forName("android.view.SemBlurInfo");
            Class<?> builderClass = Class.forName("android.view.SemBlurInfo$Builder");
            blurBuilder = builderClass.getConstructor(int.class);
            setRadius = builderClass.getMethod("setRadius", int.class);
            setColor = builderClass.getMethod("setBackgroundColor", int.class);
            setCorners = builderClass.getMethod("setBackgroundCornerRadius",
                    float.class, float.class, float.class, float.class);
            build = builderClass.getMethod("build");
            apply = View.class.getMethod("semSetBlurInfo", blurClass);
            blurApiState = 1;
            XposedBridge.log(TAG + ": Samsung SemBlurInfo blur API found");
        } catch (Throwable error) {
            blurApiState = -1;
            XposedBridge.log(TAG + ": Samsung SemBlurInfo unavailable: " + error);
        }
        return blurApiState == 1;
    }

    private static void clearBlur(View view, State value) {
        if (!value.blurApplied) return;
        value.blurApplied = false;
        value.blurRadius = -1;
        value.blurColor = Integer.MIN_VALUE;
        try {
            if (apply != null) apply.invoke(view, new Object[]{null});
        } catch (Throwable ignored) {
        }
    }

    private GlassRenderer() {}
}
