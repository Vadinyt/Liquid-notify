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
 * Draws glass directly on Samsung's existing notification background view.
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
    private static boolean loggedDrawable;

    private static final class State {
        boolean blurApplied;
        boolean blurFailed;
        int blurRadius = -1;
        float cornerRadius = -1f;
        int lastAlpha = -1;
        int lastRectW = -1;
        int lastRectH = -1;
        int lastRectLeft = Integer.MIN_VALUE;
        int lastRectTop = Integer.MIN_VALUE;
        float lastCorner = -1f;
        final Path shape = new Path();
        final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

        State() {
            sheen.setStyle(Paint.Style.FILL);
            border.setStyle(Paint.Style.STROKE);
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

    static void beforeDraw(View view, boolean active, int opacity,
                           int blurRadiusPx, float cornerRadiusPx) {
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

        if (background != null) {
            int requested = Math.max(35, Math.min(245, opacity));
            int alpha = Math.min(requested, originalAlpha(view));
            if (alpha != value.lastAlpha || background.getAlpha() != alpha) {
                background.setAlpha(alpha);
                value.lastAlpha = alpha;
                if (!loggedDrawable) {
                    loggedDrawable = true;
                    XposedBridge.log(TAG + ": native notification drawable opacity applied");
                }
            }
        }

        blurRadiusPx = Math.max(0, Math.min(80, blurRadiusPx));
        if (blurRadiusPx <= 0) {
            clearBlur(view, value);
        } else if (!value.blurFailed && view.isAttachedToWindow()
                && view.getWidth() > 0 && view.getHeight() > 0
                && (!value.blurApplied || value.blurRadius != blurRadiusPx
                || value.cornerRadius != cornerRadiusPx)) {
            if (ensureBlurApi()) {
                try {
                    Object builder = blurBuilder.newInstance(0); // Samsung MODE_WINDOW
                    setRadius.invoke(builder, blurRadiusPx);
                    setColor.invoke(builder, Color.TRANSPARENT);
                    setCorners.invoke(builder, cornerRadiusPx, cornerRadiusPx,
                            cornerRadiusPx, cornerRadiusPx);
                    apply.invoke(view, build.invoke(builder));
                    value.blurApplied = true;
                    value.blurRadius = blurRadiusPx;
                    value.cornerRadius = cornerRadiusPx;
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
                          float cornerRadiusPx) {
        if (!active) return;
        Drawable drawable = background(view);
        if (drawable == null) return;
        Rect bounds = drawable.getBounds();
        if (bounds.width() < 12 || bounds.height() < 12) return;
        State value = state(view);
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
                || value.lastCorner != corner) {
            value.lastRectW = bounds.width();
            value.lastRectH = bounds.height();
            value.lastRectLeft = bounds.left;
            value.lastRectTop = bounds.top;
            value.lastCorner = corner;
            value.shape.reset();
            value.shape.addRoundRect(left, top, right, bottom, corner, corner,
                    Path.Direction.CW);
            value.sheen.setShader(new LinearGradient(
                    left, top, left, bottom,
                    new int[]{0x18FFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.28f, 1f}, Shader.TileMode.CLAMP));
            value.border.setShader(new LinearGradient(
                    left, top, left, bottom,
                    new int[]{0x36FFFFFF, 0x13FFFFFF, 0x06FFFFFF},
                    new float[]{0f, 0.40f, 1f}, Shader.TileMode.CLAMP));
        }
        value.border.setStrokeWidth(Math.max(0.8f, edge * 0.7f));
        int save = canvas.save();
        canvas.clipPath(value.shape);
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, value.sheen);
        // Samsung's blur already provides a visual boundary. Drawing our own
        // border on top causes a second, darker ring at high blur intensity.
        if (!value.blurApplied) {
            canvas.drawRoundRect(left, top, right, bottom, corner, corner,
                    value.border);
        }
        canvas.restoreToCount(save);
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
        try {
            if (apply != null) apply.invoke(view, new Object[]{null});
        } catch (Throwable ignored) {
        }
    }

    private GlassRenderer() {}
}
