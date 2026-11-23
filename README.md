# LiquidShade

A native notification-shade material for the Galaxy S23+ running Samsung One UI 9 / Android 17. The companion and the libxposed API 102 module share one APK with the new identity `dev.vadinyt.liquidshade`. The sole LSPosed scope is **System UI** (`com.android.systemui`). The hook entry additionally checks the main process, SDK 37 and Samsung manufacturer. All modifications live in memory.

## Install on the phone

If upgrading from the 1.0.0 Actions APK, export your JSON in Diagnostics and uninstall LiquidShade first. That build used a one-time signing key. Import the JSON after installing the new APK, or select Clear for the revised optical preset.

1. Open the [LiquidShade builds](https://github.com/Vadinyt/Liquid-notify/actions?query=workflow%3A%22Build+LiquidShade+APK%22), select a successful run on `liquidshade-oneui9`, and download **LiquidShade-1.1.0** under Artifacts. GitHub may require sign-in. Extract the ZIP in My Files and tap the APK.
2. Enable LiquidShade in LSPosed and select only **System UI**. If another module already styles notification backgrounds, select one owner for that surface.
3. Restart SystemUI once from LiquidShade → Diagnostics (KernelSU permission and confirmation), or reboot. Open the unlocked notification shade. Subsequent settings apply live; the preview works without root.
4. Open the unlocked notification shade once, then open Diagnostics. “Last notification drawn” and “Last player drawn” report the most recent draw submitted by each renderer during this SystemUI session. An active count of zero is normal after you close the shade. “Awaiting first observation” means that no matching draw has been observed.
5. If the notification reports lighting only, open **Shade and footer → Choose a shade image** and select a detailed photo. Keep the shade background enabled. LiquidShade draws that image behind the cards and samples it for refraction on supported hardware. “Use system wallpaper again” removes the selection. The picker grants access to the selected image only. JSON exports contain settings only.

**Rollback.** Turn off the master switch for live restoration. To remove all hooks, disable LiquidShade in LSPosed and restart SystemUI or reboot. If the settings UI is unavailable, disable the module in LSPosed/KernelSU's module recovery environment and reboot. Uninstalling LiquidShade removes its saved settings. Export JSON first if you want to keep them.

## What is drawn

- **Notifications.** LiquidShade identifies Samsung’s normal background and two-layer recoil structure. A compatible gradient fill occupies the same drawable slot as the original. Samsung owns the row, recoil container, second layer, drawable bounds, clipping, group layout, swipe handling, actions and accessibility. Child grouping and visual spacing change the material inside those bounds. Pinned unlocked heads-up rows can use a translucent material when the shade is closed. Lock-screen rows use stock rendering.
- **Media.** For the Samsung ENR and OA player contexts, a refractive edge follows the existing album ImageView. A full-bleed, directly displayed bitmap can supply its own perimeter pixels through the actual ImageView matrix and drawable bounds. Tiled, filtered, crossfading or unrecognised drawables fall back to the aligned shade image when available, then translucent lighting. Its interior stays clear so full-bleed artwork and the controls above it remain sharp. Artwork tint reads the current album drawable only. Progress colours use the native ProgressBar tint API on compatible players. QS, cover and other player contexts are excluded.
- **Shade.** A dedicated wallpaper backing is drawn in the expanded notification panel, or the notification stack if that is the only confirmed host. The backing and its lenses use the same centre crop. The rest of the window remains under Samsung’s control. The module keeps the native status bar, clock, carrier, icons, media controls and touch targets.
- **Footer.** The existing notification settings and clear buttons receive the same material on compatible backgrounds. Both their original actions and accessibility nodes are retained.

### Backdrop and optical model

When SystemUI can read the **current static system wallpaper**, a background worker decodes a bounded image and builds six low-pass levels. The material redraws that wallpaper in the shade and samples the same image beneath each supported card. Transformation matrices map points from the drawable into the wallpaper host, including view movement and scaling. A wallpaper or configuration change invalidates the shared source. If the system image is unavailable, the user can select a private image to become the shade backing. Card sampling starts after the backing submits the same image for drawing. Decoded SystemUI sources are cleared on lock and renderer disable. Live wallpaper frames, arbitrary application windows, the lock wallpaper, notification content and secure surfaces are never captured.

An original AGSL program computes a rounded-rectangle signed distance and outward normal, with separate upper and lower radii. Displacement increases smoothly through the bevel; the centre preserves its sample position. The material adds directional rim light, an internal reflection, adjustable tint, bounded saturation and edge-only RGB separation. Media restricts the effect to its perimeter. The interactive preview uses the same shader on the selected shade image, or on a generated pattern. A sharper sample inside the bevel preserves the bent colour boundaries across a softly blurred centre.

With unavailable wallpaper pixels, an unsupported transform, or unavailable hardware shaders, the material uses native transparency. With hardware shaders, contact position drives a directional highlight, an internal reflection and a damped squeeze even without readable pixels. Canvas supplies shaded edges and a geometric squeeze on the software fallback. The isolated shade backing requests an Android compositor blur region if the window supports it; Samsung’s underlying shade blur may also be present. Unsupported OEM structures keep their native surface. Refraction, optical blur and RGB separation require image pixels. Diagnostics records the last wallpaper lookup, the per-surface backend and the number of springs started. Media outside the sampled host keeps a translucent edge. A wallpaper that is already heavily blurred or nearly uniform naturally shows little visible refraction.

### Settings

| Category | Controls |
| --- | --- |
| General | Enable, draggable preview, Clear/Balanced/Frosted/Dark Glass/Custom, wallpaper/theme adaptation, Quality/Balanced/Battery Saver, reset |
| Optics | Refraction, blur, compression, depth, bevel, dispersion, reflections, rim, tint colour and opacity, saturation and vibrancy |
| Cards | Enable, opacity, radius, border, visual spacing, highlight colour, contrast protection, stock/joined/individual group children, press response |
| Media | Enable, blur, refraction, artwork tint, opacity, radius, stock/highlight/artwork progress colour, rim |
| Shade | Enable, optional local image, wallpaper blur, dim, tint colour and intensity, gradient, glass footer |
| Motion | Enable, stiffness, damping, speed, follow system motion settings, replay in preview. The scoped row dispatch observer forwards press, move, release and cancellation to the material. Expansion, dismissal and movement trigger additional impulses. The spring affects the material only; SystemUI controls view movement. |
| Advanced | Live compatibility, backend, protection, diagnostics, JSON import/export, confirmed SystemUI restart |

The 0–100 optics sliders map to physical ranges. Refraction displacement is up to 22 dp before the compression factor. Bevel is approximately 1.2–28.7 dp and capped by the half-size of the surface. Dispersion is at most 0.85 dp per side in Quality. Pane and material blur contribute up to 16 and 22 dp respectively before adaptive scaling. Tint layers are capped to preserve the backdrop. Quality/Balanced/Battery Saver bound the wallpaper’s long edge to 1920/1280/720 pixels. Battery Saver uses a prefiltered image, a single backdrop sample, and disables springs. Named presets adjust optical, card, media and shade values while preserving component switches, touch preferences and group layout. Colour controls accept RGB hex. French and English follow Android’s locale.

### Compatibility evidence and limits

The inspected `com.android.systemui` APK reports version 17, version code 37 and SDK 37. Its SHA-256 is `d6c164100707987a7dad8405f7c90d1c9af82b62db0444354f0decf778e8bb50`. The APK establishes the presence of these paths; it does not establish which paths are active on every One UI 9 build.

| Component | Evidence checked in Samsung DEX/resources |
| --- | --- |
| Shade | NotificationStackScrollLayout attachment, expansion, QS fraction and bar-state methods/fields; NotificationPanelView and notification stack resource |
| Notification | ActivatableNotificationView.dispatchTouchEvent(MotionEvent) returning boolean; ExpandableNotificationRow lifecycle/group/keyguard methods; NotificationBackgroundView.mBackground and setCustomBackground(SeslRecoilDrawable); notification_material_bg, notification_focus_overlay, radial_gradient_view |
| Blur | The inspected NotificationBackgroundView.isBlurEnabled() returns false. Global hook suppression of Samsung blur would also affect other contexts and is unnecessary on this path. |
| Media | SecMediaControlPanel.attach and bind; mType, mViewHolder, playerView; ENR/OA/QS distinctions; sec_album_art and sec_media_progress_bar |
| Footer | sec_status_bar_notification_shelf places noti_setting and clear_all inside NotificationShelf. Both are native text controls. |

Binding requires matching runtime types, names, resource identities and attachment. The renderer restores only installations it still owns. Configuration and status IPC require the privileged SystemUI permission and check the caller UID. Diagnostic logging contains error class names only. Three recorded adapter errors suspend modifications until the user clears protection. This counts module-caught failures; it cannot diagnose every native crash or GPU-driver reset.

No physical-device test has been performed for this build. The exact media renderer, winning OEM layer, wallpaper crop, compositor support, grouped/heads-up appearance, touch behaviour and 120 Hz frame pacing on the installed firmware remain unverified. A uniform wallpaper and fully opaque artwork can hide much of the optical effect.

## Build

Android Studio/Gradle project, Java 17, Gradle 8.11.1 (wrapper + distribution checksum), AGP 8.9.2, compile/target SDK 35, Build Tools 35.0.0. The APK’s public API calls fit SDK 35. The hook is intentionally gated to the inspected API 37 host. Maven Central supplies the real `io.github.libxposed:api:102.0.0` as a compile-only dependency.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

GitHub Actions runs the configuration tests and lint, renders the AGSL source on desktop Skia against a known image to check its pixel contract, verifies the signed APK, and uploads the APK and checksum. CI uses a dedicated LiquidShade debug-format JKS from the optional Actions secret LIQUIDSHADE_SIGNING_JKS_B64, or generates a fresh key per run if absent. The optional key uses the Gradle debug alias and passwords and must belong exclusively to LiquidShade. To move between artifacts signed by different keys, export settings, uninstall the old LiquidShade APK, install the new one, re-enable the scope and import settings. Keys from other modules are never used.

See [third-party acknowledgements](THIRD_PARTY.md).
