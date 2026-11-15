package dev.vadinyt.liquidnotify;

import android.app.KeyguardManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import java.util.Locale;
import java.util.WeakHashMap;
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
    private static volatile boolean radiusEnabled = true;
    private static volatile int radiusDp = 44;
    private static volatile int opacity = 104;
    private static volatile int palette = 0;
    private static volatile int blurPx = 28;
    private static volatile int liquidStrength = 86;
    private static volatile boolean loggedRadius;
    private static volatile boolean loggedTint;
    private static volatile int lastLoggedSignature = Integer.MIN_VALUE;
    private static final WeakHashMap<View, Boolean> DIMMED_CACHE = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> LIVE_VIEWS = new WeakHashMap<>();
    private static volatile boolean observerRegistered;
    // Samsung rebinds notification backgrounds as rows expand, collapse or update.
    // Keep a weak set of active view instances and invalidate on settings changes.
    private static void watch(View view) {
        synchronized (LIVE_VIEWS) { LIVE_VIEWS.put(view, Boolean.TRUE); }
        if (observerRegistered) return;
        synchronized (MainHook.class) {
            if (observerRegistered) return;
            try {
                ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) {
                        onStyleChanged();
                    }
                };
                String[] names = {"liquidnotify_enabled", "liquidnotify_opacity",
                        "liquidnotify_palette", "liquidnotify_radius_dp", "liquidnotify_blur_px",
                        "liquidnotify_liquid_strength"};
                for (String name : names) {
                    view.getContext().getContentResolver().registerContentObserver(
                            Settings.Global.getUriFor(name), false, observer);
                }
                observerRegistered = true;
                XposedBridge.log(TAG + ": live settings observer registered");
            } catch (Throwable e) {
                XposedBridge.log(TAG + ": settings observer registration failure " + e);
            }
        }
    }

    private static void onStyleChanged() {
        lastRead = -1;
        int count = 0;
        synchronized (LIVE_VIEWS) {
            for (View view : LIVE_VIEWS.keySet()) {
                if (view != null) {
                    view.post(() -> {
                        if (shouldStyle(view)) {
                            // Force Samsung to receive the latest palette even
                            // if its own setTint() does not fire on this update.
                            try {
                                java.lang.reflect.Method method =
                                        view.getClass().getDeclaredMethod("setTint", int.class);
                                method.setAccessible(true);
                                method.invoke(view, baseColor(isNight(view), palette));
                            } catch (Throwable ignored) {
                                // The draw-pass palette wash is the fallback.
                            }
                        }
                        view.invalidate();
                    });
                    count++;
                }
            }
        }
        XposedBridge.log(TAG + ": live style refresh, views=" + count);
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.android.systemui".equals(param.packageName)
                || !"com.android.systemui".equals(param.processName)) return;
        XposedBridge.log(TAG + ": v0.16 Samsung native glass renderer");
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
                        if (!shouldStyle(view)) return;
                        int base = baseColor(isNight(view), palette);
                        // IMPORTANT: apply opacity only once. Older releases made both
                        // the tint and the drawable translucent, multiplying alpha.
                        // For Samsung's native mBackground, keep the tint fully opaque
                        // and apply card alpha in GlassRenderer.beforeDraw().
                        // Tint remains fully opaque. All background pixels are
                        // composited at the selected alpha in the onDraw hook,
                        // preventing multiple independent alpha multiplications.
                        p.args[0] = base;
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
                        watch(view);
                        boolean active = shouldStyle(view);
                        // Dimmed views also need the matching palette, but two
                        // simultaneous background blurs create ghost outlines.
                        int effectiveBlur = isDimmed(view) ? 0 : blurPx;
                        GlassRenderer.beforeDraw(view, (Canvas) p.args[0], active,
                                opacity, effectiveBlur, dp(view, radiusDp), palette);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": glass beforeDraw failure " + t);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        View view = (View) p.thisObject;
                        boolean active = shouldStyle(view);
                        GlassRenderer.afterDraw(view, (Canvas) p.args[0],
                                active, dp(view, radiusDp), palette,
                                isDimmed(view), opacity, liquidStrength);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": glass afterDraw failure " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": v0.16 glass draw hooks registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": glass draw hook registration failed " + t);
        }
        XposedBridge.log(TAG + ": v0.16 hooks registered");
    }

    private static boolean isDimmed(View view) {
        synchronized (DIMMED_CACHE) {
            Boolean cached = DIMMED_CACHE.get(view);
            if (cached != null) return cached;
            boolean dimmed = false;
            try {
                if (view.getId() != View.NO_ID) {
                    String id = view.getResources().getResourceEntryName(view.getId());
                    dimmed = id.toLowerCase(Locale.ROOT).contains("dimmed");
                }
            } catch (Throwable ignored) {
                // Samsung may rename this OEM resource. Leave it eligible
                // rather than hiding any background when identification fails.
            }
            DIMMED_CACHE.put(view, dimmed);
            return dimmed;
        }
    }

    private static int baseColor(boolean night, int selected) {
        switch (selected) {
            case 1: return night ? 0xFFC2CBD9 : 0xFFE4E9F1; // pale graphite
            case 2: return night ? 0xFFCEB7EC : 0xFFEFDAFF; // pearl violet
            default: return night ? 0xFFBADDF9 : 0xFFE5F5FF; // clear sky blue
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
                        radiusEnabled = readInt(context, "liquidnotify_radius_enabled", 1, 0, 1) == 1;
                        radiusDp = readInt(context, "liquidnotify_radius_dp", 44, 18, 48);
                        opacity = readInt(context, "liquidnotify_opacity", 104, 35, 245);
                        palette = readInt(context, "liquidnotify_palette", 0, 0, 2);
                        blurPx = readInt(context, "liquidnotify_blur_px", 28, 0, 80);
                        liquidStrength = readInt(context, "liquidnotify_liquid_strength", 86, 0, 100);
                        if (lastConfigLog < 0 || now - lastConfigLog > 30000L) {
                            lastConfigLog = now;
                            XposedBridge.log(TAG + ": SystemUI reads settings enabled=" + enabled
                                    + " radius=" + radiusEnabled
                                    + " radiusDp=" + radiusDp + " opacity=" + opacity
                                    + " palette=" + palette + " blurPx=" + blurPx
                                    + " liquid=" + liquidStrength);
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
