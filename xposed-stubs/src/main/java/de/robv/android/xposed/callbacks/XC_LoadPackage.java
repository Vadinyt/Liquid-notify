package de.robv.android.xposed.callbacks;

/**
 * Compile-time-only ABI stub. Runtime implementation is supplied by LSPosed.
 * Never package this class in the APK.
 */
public abstract class XC_LoadPackage {
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}
