package dev.vadinyt.liquidnotify;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LiquidNotify unified: configurable native card tint and shape.
 * Uses Samsung SemBlurInfo per notification when available, with a clipped sheen fallback.
 * Settings.Global keys can be changed using an authorized adb/root shell.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "LiquidNotify";
    private static final String BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    private static volatile long lastRead = -1;
    private static volatile long lastConfigLog = -1;
    private static volatile boolean enabled = true;
    private static volatile boolean colorEnabled = true;
    private static volatile boolean radiusEnabled = true;
    private static volatile int radiusDp = 36;
    private static volatile int opacity = 185;
    private static volatile int palette = 0;
    private static volatile int blurPx = 64;
    private static volatile boolean loggedRadius;
    private static volatile boolean loggedTint;
    private static volatile int lastLoggedSignature = Integer.MIN_VALUE;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)
                || !"com.android.systemui".equals(param.processName)) return;
        XposedBridge.log(TAG + ": v0.10 Samsung native glass renderer");
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "setRadius", float.class, float.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        if (!shouldStyle(view) || !radiusEnabled) return;
                        float radius = dp(view, radiusDp);
                        // Apply the requested radius, including smaller values.
                        p.args[0] = radius;
                        p.args[1] = radius;
                        view.invalidate();
                        if (!loggedRadius) {
                            loggedRadius = true;
                            XposedBridge.log(TAG + ": configurable radius hook active");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": radius failure " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": radius registration failed " + t); }
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "setTint", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        if (!shouldStyle(view) || !colorEnabled) return;
                        int base = baseColor(isNight(view), palette);
                        // IMPORTANT: apply opacity only once. Older releases made both
                        // the tint and the drawable translucent, multiplying alpha.
                        // For Samsung's native mBackground, keep the tint fully opaque
                        // and apply card alpha in GlassRenderer.beforeDraw().
                        p.args[0] = GlassRenderer.hasNativeBackground(view)
                                ? base
                                : Color.argb(opacity, Color.red(base),
                                        Color.green(base), Color.blue(base));
                        int signature = (palette << 16) | (opacity << 8) | radiusDp;
                        if (signature != lastLoggedSignature) {
                            lastLoggedSignature = signature;
                            XposedBridge.log(TAG + ": applied configuration palette=" + palette
                                    + " opacity=" + opacity + " radiusDp=" + radiusDp
                                    + " tint=0x" + Integer.toHexString((Integer) p.args[0]));
                        }
                        view.invalidate();
                        if (!loggedTint) {
                            loggedTint = true;
                            XposedBridge.log(TAG + ": palette/opacity hook active");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": tint failure " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": tint registration failed " + t); }
        // Use the OEM NotificationBackgroundView draw pass. The glass effect
        // stays behind the notification text, icons, and touch handling.
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        boolean active = shouldStyle(view) && colorEnabled;
                        GlassRenderer.beforeDraw(view, active, opacity, blurPx,
                                dp(view, radiusDp));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": glass beforeDraw failure " + t);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        boolean active = shouldStyle(view) && colorEnabled;
                        GlassRenderer.afterDraw(view, (Canvas) p.args[0],
                                active, dp(view, radiusDp));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": glass afterDraw failure " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": v0.10 glass draw hooks registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": glass draw hook registration failed " + t);
        }
        XposedBridge.log(TAG + ": v0.10 hooks registered");
    }

    private static int baseColor(boolean night, int selected) {
        switch (selected) {
            case 1: return night ? 0xFF282B34 : 0xFF434852; // graphite
            case 2: return night ? 0xFF302B45 : 0xFF55476D; // violet
            default: return night ? 0xFF303A50 : 0xFF43546C; // icy blue
        }
    }

    private static float dp(View v, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                v.getResources().getDisplayMetrics());
    }

    private static boolean isNight(View v) {
        return (v.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int readInt(Context c, String key, int fallback, int min, int max) {
        int value = Settings.Global.getInt(c.getContentResolver(), key, fallback);
        return Math.max(min, Math.min(max, value));
    }

    private static boolean shouldStyle(View view) {
        Context context = view.getContext();
        long now = SystemClock.uptimeMillis();
        if (lastRead < 0 || now - lastRead >= 1500L) {
            synchronized (MainHook.class) {
                if (lastRead < 0 || now - lastRead >= 1500L) {
                    try {
                        enabled = readInt(context, "liquidnotify_enabled", 1, 0, 1) == 1;
                        colorEnabled = readInt(context, "liquidnotify_color", 1, 0, 1) == 1;
                        radiusEnabled = readInt(context, "liquidnotify_radius_enabled", 1, 0, 1) == 1;
                        radiusDp = readInt(context, "liquidnotify_radius_dp", 36, 18, 48);
                        opacity = readInt(context, "liquidnotify_opacity", 185, 35, 245);
                        palette = readInt(context, "liquidnotify_palette", 0, 0, 2);
                        blurPx = readInt(context, "liquidnotify_blur_px", 64, 0, 80);
                        if (lastConfigLog < 0 || now - lastConfigLog > 30000L) {
                            lastConfigLog = now;
                            XposedBridge.log(TAG + ": SystemUI reads settings enabled=" + enabled
                                    + " color=" + colorEnabled + " radius=" + radiusEnabled
                                    + " radiusDp=" + radiusDp + " opacity=" + opacity
                                    + " palette=" + palette + " blurPx=" + blurPx);
                        }
                    } catch (Throwable t) {
                        enabled = false;
                        XposedBridge.log(TAG + ": config read failure " + t);
                    }
                    lastRead = now;
                }
            }
        }
        if (!enabled) return false;
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && !km.isKeyguardLocked();
        } catch (Throwable t) { return false; }
    }
}
