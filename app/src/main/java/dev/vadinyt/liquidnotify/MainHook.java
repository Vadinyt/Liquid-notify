package dev.vadinyt.liquidnotify;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * v0.4 native notification glass styling for One UI 9.
 * Works on Samsung's existing notification background view. It does not replace
 * the shade, alter notification content, or intercept touch handling.
 *
 * Note: glass highlights are native drawables, not a verified backdrop blur.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "LiquidNotify";
    private static final String BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    private static final float RADIUS_DP = 34f;
    private static final Object LOCK = new Object();
    private static final WeakHashMap<View, Boolean> decorated = new WeakHashMap<>();
    private static volatile long lastRead;
    private static volatile boolean enabled = true;
    private static volatile boolean safeMode;
    private static volatile boolean colorEnabled = true;
    private static volatile boolean highlightsEnabled = true;
    private static volatile boolean loggedRadius;
    private static volatile boolean loggedTint;
    private static volatile boolean loggedGlass;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)
                || !"com.android.systemui".equals(param.processName)) return;
        XposedBridge.log(TAG + ": v0.4 glass surfaces, main process only");
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "setRadius", float.class, float.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        if (!shouldStyle(view)) return;
                        float radius = dp(view, RADIUS_DP);
                        p.args[0] = Math.max((Float) p.args[0], radius);
                        p.args[1] = Math.max((Float) p.args[1], radius);
                        decorate(view);
                        if (!loggedRadius) {
                            loggedRadius = true;
                            XposedBridge.log(TAG + ": radius hook active");
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
                        if (!shouldStyle(view)) return;
                        if (colorEnabled) {
                            boolean night = isNight(view);
                            // Retain a dark readable base on the unlocked notification shade.
                            p.args[0] = night ? 0xE92C3447 : 0xE9384963;
                        }
                        decorate(view);
                        if (!loggedTint) {
                            loggedTint = true;
                            XposedBridge.log(TAG + ": adaptive tint hook active");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": tint failure " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": tint registration failed " + t); }
        XposedBridge.log(TAG + ": v0.4 hook registration complete");
    }

    private static float dp(View v, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                v.getResources().getDisplayMetrics());
    }

    private static boolean isNight(View v) {
        return (v.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void decorate(View view) {
        if (!highlightsEnabled || view.getForeground() != null) return;
        synchronized (LOCK) {
            if (decorated.containsKey(view)) return;
            decorated.put(view, true);
        }
        try {
            // Transparent overlay: a restrained upper sheen and fine outline.
            // Foreground does not consume taps or replace Samsung's background drawable.
            GradientDrawable glass = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x20FFFFFF, 0x08FFFFFF, 0x00FFFFFF});
            glass.setCornerRadius(dp(view, RADIUS_DP));
            glass.setStroke(Math.max(1, Math.round(dp(view, 0.8f))),
                    isNight(view) ? 0x42FFFFFF : 0x56FFFFFF);
            view.setForeground(glass);
            if (!loggedGlass) {
                loggedGlass = true;
                XposedBridge.log(TAG + ": glass highlight drawable active (no backdrop blur)");
            }
        } catch (Throwable t) {
            synchronized (LOCK) { decorated.remove(view); }
            XposedBridge.log(TAG + ": glass highlight failed " + t);
        }
    }

    private static boolean shouldStyle(View view) {
        Context context = view.getContext();
        long now = SystemClock.uptimeMillis();
        if (now - lastRead >= 2000L) {
            try {
                enabled = Settings.Global.getInt(context.getContentResolver(),
                        "liquidnotify_enabled", 1) == 1;
                safeMode = Settings.Global.getInt(context.getContentResolver(),
                        "liquidnotify_safe_mode", 0) == 1;
                colorEnabled = Settings.Global.getInt(context.getContentResolver(),
                        "liquidnotify_color", 1) == 1;
                highlightsEnabled = Settings.Global.getInt(context.getContentResolver(),
                        "liquidnotify_highlights", 1) == 1;
            } catch (Throwable t) {
                enabled = false;
                XposedBridge.log(TAG + ": config read failed " + t);
            }
            lastRead = now;
        }
        if (!enabled || safeMode) return false;
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && !km.isKeyguardLocked();
        } catch (Throwable t) { return false; }
    }
}
