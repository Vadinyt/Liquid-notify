# LiquidNotify v0.11.0: Clipped native glass

LiquidNotify modifies Samsung's existing notification background views in `com.android.systemui`. It does not replace the notification center or intercept notifications, gestures, expansion, or dismissal.

**New experimental effect:** tries Samsung's `View.semSetBlurInfo(SemBlurInfo)` with a rounded native blur region for each notification, and adds subtle *in-bounds* sheen and rim rendering directly in the notification background's draw pass. This OEM API was reported on One UI 8.5, but is **not yet verified on Galaxy S23+ One UI 9 / Android 17**. When unavailable, blur is skipped and the tinted cards remain.

## Controls

Open the LiquidNotify launcher application and allow root in KernelSU. Values are saved in `Settings.Global` and read by SystemUI.

| Setting | Internal key | Range | Default |
| --- | --- | --- | --- |
| Enable styling | `liquidnotify_enabled` | 0 or 1 | 1 |
| Card opacity | `liquidnotify_opacity` | 35–245 (higher is more opaque) | 105 |
| Native blur strength | `liquidnotify_blur_px` | 0–80 pixels, 0 disables blur | 64 |
| Card radius | `liquidnotify_radius_dp` | 18–48 dp | 36 |
| Color palette | `liquidnotify_palette` | 0 icy blue / 1 graphite / 2 violet | 0 |

Legacy expert toggles `liquidnotify_color` and `liquidnotify_radius_enabled` remain available as 0/1 values.

The app now provides an **Apply Frosted Glass Preset** button setting opacity 112, blur 54 px and radius 38 dp. The native tint remains fully opaque; opacity is applied once to Samsung's drawable, correcting earlier double multiplication of alpha. A single clipped inner sheen is used; the extra stroke is omitted when native blur is active.

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

## V0.11 fixes

- Restricts Samsung native background blur to the drawable bounds using a rounded View outline; restores the original View outline when blur is off.
- Skips separately named dimmed notification backgrounds to avoid two simultaneous backdrop surfaces.
- Draws a softer interior sheen and uses brighter tint palettes.
- Changes the Frosted Glass preset to opacity 112 and 54 px blur.

The dimmed-background detection uses the Android resource entry name; OEM builds may use other names. Real-device verification on One UI 9 remains necessary.
