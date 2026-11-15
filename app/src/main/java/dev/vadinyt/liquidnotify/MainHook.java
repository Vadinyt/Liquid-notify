package dev.vadinyt.liquidnotify;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Field;
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
 * No replacement shade, touch interception, or fake backdrop blur.
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
    private static volatile boolean loggedRadius;
    private static volatile boolean loggedTint;
    private static volatile boolean loggedDrawable;
    private static volatile boolean loggedMissingDrawable;
    private static volatile int lastLoggedSignature = Integer.MIN_VALUE;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)
                || !"com.android.systemui".equals(param.processName)) return;
        XposedBridge.log(TAG + ": unified native glass surfaces");
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
                        p.args[0] = Color.argb(opacity, Color.red(base),
                                Color.green(base), Color.blue(base));
                        int signature = (palette << 16) | (opacity << 8) | radiusDp;
                        if (signature != lastLoggedSignature) {
                            lastLoggedSignature = signature;
                            XposedBridge.log(TAG + ": applied configuration palette=" + palette
                                    + " opacity=" + opacity + " radiusDp=" + radiusDp
                                    + " tint=0x" + Integer.toHexString((Integer) p.args[0]));
                        }
                        applyDrawableOpacity(view);
                        view.invalidate();
                        if (!loggedTint) {
                            loggedTint = true;
                            XposedBridge.log(TAG + ": palette/opacity hook active");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": tint failure " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": tint registration failed " + t); }
        // Samsung may overwrite drawable alpha while updating notification tint.
        // Reapply just before drawing the card background, never the content row.
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.thisObject instanceof View) {
                            View view = (View) p.thisObject;
                            if (shouldStyle(view) && colorEnabled) applyDrawableOpacity(view);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": drawable draw failure " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": drawable alpha hook registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": drawable alpha registration unavailable " + t);
        }
        XposedBridge.log(TAG + ": unified hooks registered");
    }

    private static void applyDrawableOpacity(View view) {
        // mBackground is the drawable painted by NotificationBackgroundView,
        // not the View's background or the notification content.
        try {
            Field field = view.getClass().getDeclaredField("mBackground");
            field.setAccessible(true);
            Object object = field.get(view);
            if (!(object instanceof Drawable)) return;
            Drawable drawable = (Drawable) object;
            drawable.setAlpha(opacity);
            if (!loggedDrawable) {
                loggedDrawable = true;
                XposedBridge.log(TAG + ": native background drawable alpha active: " + opacity);
            }
        } catch (NoSuchFieldError e) {
            if (!loggedMissingDrawable) {
                loggedMissingDrawable = true;
                XposedBridge.log(TAG + ": mBackground not found on this One UI build");
            }
        } catch (Throwable t) {
            if (!loggedMissingDrawable) {
                loggedMissingDrawable = true;
                XposedBridge.log(TAG + ": drawable opacity unavailable " + t);
            }
        }
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
                        if (lastConfigLog < 0 || now - lastConfigLog > 30000L) {
                            lastConfigLog = now;
                            XposedBridge.log(TAG + ": SystemUI reads settings enabled=" + enabled
                                    + " color=" + colorEnabled + " radius=" + radiusEnabled
                                    + " radiusDp=" + radiusDp + " opacity=" + opacity
                                    + " palette=" + palette);
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
