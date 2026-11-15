# LiquidNotify v0.3.0-experimental: test protocol

**Experimental build. No successful build or phone test has been verified yet.**

1. Download APK from GitHub Actions after a successful run.
2. Install, enable module in LSPosed, scope ONLY com.android.systemui.
3. Restart SystemUI (or reboot). Make sure you can recover from a SystemUI restart loop using LSPosed safe mode.
4. Open unlocked notification shade. Inspect corner radius and transparency. Test expanding, dismissing, grouping, replying.
5. Inspect Xposed logs for LiquidNotify entries.
6. To disable effects without uninstalling: `adb shell settings put global liquidnotify_safe_mode 1`; restart SystemUI if needed.
7. To re-enable: `adb shell settings put global liquidnotify_safe_mode 0`.
8. To disable all module visual styling: `adb shell settings put global liquidnotify_enabled 0`.
9. If SystemUI is unstable, disable LiquidNotify from LSPosed and reboot.

The initial implementation does NOT include native backdrop blur, gradient cards, header redesign or settings UI. It modifies native Samsung notification background radius and uses a muted blue-grey tint. It does not yet provide actual backdrop blur or multicolor gradients. To disable color only: `adb shell settings put global liquidnotify_color 0`; to enable: `adb shell settings put global liquidnotify_color 1`. Lock screen is intentionally excluded.

**Known limitation:** tint is refreshed when Samsung invokes setTint; radius changes are applied on the next setRadius call. The settings are polled at most once every two seconds on hook invocation, not continuously.
