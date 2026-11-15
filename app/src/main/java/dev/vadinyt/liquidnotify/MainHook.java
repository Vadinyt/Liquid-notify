package dev.vadinyt.liquidnotify;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * First visual prototype: modifies native Samsung NotificationBackgroundView only.
 * No notification data, touch, security or ranking hooks.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "LiquidNotify";
    private static final String BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    private static volatile long lastCheck;
    private static volatile boolean enabled = true;
    private static volatile boolean safeMode = false;
    private static volatile boolean loggedRadius;
    private static volatile boolean loggedTint;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)) return;
        XposedBridge.log(TAG + ": starting v0.2.0-test on " + param.packageName);
        try {
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "setRadius", float.class, float.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        if (!shouldStyle(view)) return;
                        float target = view.getResources().getDisplayMetrics().density * 28f;
                        p.args[0] = Math.max((Float) p.args[0], target);
                        p.args[1] = Math.max((Float) p.args[1], target);
                        if (!loggedRadius) {
                            loggedRadius = true;
                            XposedBridge.log(TAG + ": native card radius hook active");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": radius hook error: " + t); }
                }
            });
            XposedHelpers.findAndHookMethod(BACKGROUND, param.classLoader,
                    "setTint", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        // Alter only the existing native background surface, not its contents.
                        view.setAlpha(shouldStyle(view) ? 0.89f : 1f);
                        if (!loggedTint) {
                            loggedTint = true;
                            XposedBridge.log(TAG + ": native card translucency hook active");
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": tint hook error: " + t); }
                }
            });
            XposedBridge.log(TAG + ": hook registration complete");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook registration FAILED: " + t);
        }
    }

    private static boolean shouldStyle(View view) {
        Context context = view.getContext();
        long now = SystemClock.uptimeMillis();
        if (now - lastCheck > 2000L) {
            try {
                enabled = Settings.Global.getInt(context.getContentResolver(),
                        "liquidnotify_enabled", 1) == 1;
                safeMode = Settings.Global.getInt(context.getContentResolver(),
                        "liquidnotify_safe_mode", 0) == 1;
            } catch (Throwable t) {
                enabled = false; // Fail closed if settings cannot be read.
            }
            lastCheck = now;
        }
        if (!enabled || safeMode) return false;
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && !km.isKeyguardLocked();
    }
}
