package dev.vadinyt.liquidnotify;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * v0.3 experimental native-surface styling.
 * Does not replace, intercept, or reorder notification content.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "LiquidNotify";
    private static final String BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    // Muted cool tint chosen for contrast with Samsung's existing white foreground.
    private static final int COOL_TINT = 0xFF35445C;
    private static volatile long lastRead;
    private static volatile boolean enabled = true;
    private static volatile boolean safeMode;
    private static volatile boolean colorEnabled = true;
    private static volatile boolean loggedRadius;
    private static volatile boolean loggedTint;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)
                || !"com.android.systemui".equals(param.processName)) return;
        XposedBridge.log(TAG + ": v0.3 experimental, main process only");
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "setRadius", float.class, float.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        if (!shouldStyle(view)) return;
                        float radius = 30f * view.getResources().getDisplayMetrics().density;
                        p.args[0] = Math.max((Float) p.args[0], radius);
                        p.args[1] = Math.max((Float) p.args[1], radius);
                        if (!loggedRadius) {
                            loggedRadius = true;
                            XposedBridge.log(TAG + ": radius hook invoked");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": radius failure " + t); }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": radius registration failed " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "setTint", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        if (!shouldStyle(view)) return;
                        if (colorEnabled) p.args[0] = COOL_TINT;
                        if (!loggedTint) {
                            loggedTint = true;
                            XposedBridge.log(TAG + ": tint hook invoked");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": tint failure " + t); }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": tint registration failed " + t);
        }
        XposedBridge.log(TAG + ": hook registration finished");
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
        } catch (Throwable t) {
            return false;
        }
    }
}
