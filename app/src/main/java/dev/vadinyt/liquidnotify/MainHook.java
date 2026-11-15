package dev.vadinyt.liquidnotify;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedBridge;

/** Diagnostic-only entry point; no visual hooks. */
public final class MainHook implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)) return;
        XposedBridge.log("LiquidNotify: loaded in SystemUI; visual hooks disabled (discovery mode)");
    }
}
