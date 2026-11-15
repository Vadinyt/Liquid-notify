# LiquidNotify v0.9.0: Samsung native glass preview

LiquidNotify modifies Samsung's existing notification background views in `com.android.systemui`. It does not replace the notification center or intercept notifications, gestures, expansion, or dismissal.

**New experimental effect:** tries Samsung's `View.semSetBlurInfo(SemBlurInfo)` with a rounded native blur region for each notification, and adds subtle *in-bounds* sheen and rim rendering directly in the notification background's draw pass. This OEM API was reported on One UI 8.5, but is **not yet verified on Galaxy S23+ One UI 9 / Android 17**. When unavailable, blur is skipped and the tinted cards remain.

## Controls

Open the LiquidNotify launcher application and allow root in KernelSU. Values are saved in `Settings.Global` and read by SystemUI.

| Setting | Internal key | Range | Default |
| --- | --- | --- | --- |
| Enable styling | `liquidnotify_enabled` | 0 or 1 | 1 |
| Card opacity | `liquidnotify_opacity` | 35–245 (higher is more opaque) | 185 |
| Native blur strength | `liquidnotify_blur_px` | 0–120 pixels, 0 disables blur | 88 |
| Card radius | `liquidnotify_radius_dp` | 18–48 dp | 36 |
| Color palette | `liquidnotify_palette` | 0 icy blue / 1 graphite / 2 violet | 0 |

Legacy expert toggles `liquidnotify_color` and `liquidnotify_radius_enabled` remain available as 0/1 values.

The effect only operates on the unlocked notification shade. A soft restart of SystemUI may help initial hook loading; later changes should appear when notification backgrounds are redrawn.

## Diagnostic logs

Look for:
- `Samsung SemBlurInfo blur API found`: reflective method lookup succeeded
- `Samsung SemBlurInfo native card blur applied`: Samsung accepted the blur request
- `Samsung SemBlurInfo unavailable`: API not available on this firmware
- `Samsung native card blur failed`: applying the OEM blur threw an exception
- `native notification drawable opacity applied`: background alpha adjustment ran

An accepted blur request does not by itself prove a visible blur effect. Compare photos with blur 0 px and 100 px at a low opacity over a wallpaper with varied colors.

## Recovery

If SystemUI does not render correctly, disable LiquidNotify in LSPosed and restart the phone. The app also has a normal enable/disable toggle. There is no separate safe mode.

## Engineering limitations

Samsung may draw extra backing layers around notification rows, beyond `NotificationBackgroundView.mBackground`. The module does not alter those other layers. Blurring each view may depend on GPU support, OEM blur policy, power saving, or view attachment; the native API can be present yet ineffective.

No overlay windows or foreground drawables are created. The module does not modify the keyboard or other applications.
