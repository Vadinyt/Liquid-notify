# LiquidShade

A native notification-shade material for the Galaxy S23+ running Samsung One UI 9 / Android 17. The companion and the libxposed API 102 module share one APK with the new identity `dev.vadinyt.liquidshade`. Version 1.5.0 follows the current native shade by default. Each supported notification receives a clear face, directional highlights and a live optical filter when Android supplies a drawable backdrop. The sole LSPosed scope is **System UI** (`com.android.systemui`). The hook entry additionally checks the main process, SDK 37 and Samsung manufacturer. All modifications live in memory.

## Install on the phone

If upgrading from a 1.0–1.4 one-time signed Actions APK, export your JSON in Diagnostics and uninstall LiquidShade first. That build used a one-time signing key. Import the JSON after installing the new APK, or select Clear for the revised optical preset.

1. Open the [LiquidShade builds](https://github.com/Vadinyt/Liquid-notify/actions?query=workflow%3A%22Build+LiquidShade+APK%22), select a successful run on `liquidshade-oneui9`, and download **LiquidShade-1.5.0** under Artifacts. GitHub may require sign-in. Extract the ZIP in My Files and tap the APK.
2. Enable LiquidShade in LSPosed and select only **System UI**. If another module already styles notification backgrounds, select one owner for that surface.
3. Restart SystemUI once from LiquidShade → Diagnostics (KernelSU permission and confirmation), or reboot. Open the unlocked notification shade. Subsequent settings apply live; the preview works without root.
4. Open the unlocked notification shade once, then open Diagnostics. “Last notification drawn” and “Last player drawn” report the most recent draw submitted by each renderer during this SystemUI session. An active count of zero is normal after you close the shade. “Awaiting first observation” means that no matching draw has been observed.
5. The default uses the current One UI backdrop, including after an import from 1.4. To deliberately replace it, choose a photo in **Shade and footer**. The photo switch allows returning to the live shade while retaining the selected file. JSON contains configuration only.

**Rollback.** Turn off the master switch for live restoration. To remove all hooks, disable LiquidShade in LSPosed and restart SystemUI or reboot. If the settings UI is unavailable, disable the module in LSPosed/KernelSU's module recovery environment and reboot. Uninstalling LiquidShade removes its saved settings. Export JSON first if you want to keep them.

## What is drawn

- **Notifications.** LiquidShade identifies Samsung’s normal background and two-layer recoil structure. A compatible gradient fill occupies the same drawable slot as the original. Samsung owns the row, recoil container, interactive feedback, drawable bounds, clipping, group layout, swipe handling, actions and accessibility. Samsung can apply an opaque SRC colour to the radial state layer. The adapter suppresses its idle drawing, forwards native interaction states and restores the original instance and colour on removal. Child grouping and visual spacing change the material inside those bounds. Pinned unlocked heads-up rows can use a translucent material when the shade is closed. Lock-screen rows use stock rendering.
- **Media.** One perimeter attaches to the verified rounded Samsung player container in ENR, OA or a structurally verified notification host. Its centre is transparent. The adapter checks both the main album image and the shade album slot. It samples a stable full-size bitmap with a matching image matrix; layered artwork and transitions keep the perimeter lighting. Samsung draws the artwork and playback controls. Compatible seek bars support native progress tint.
- **Shade.** One UI owns the current backdrop and any cross-window blur. The module adds a bounded shade tint and filters available pixels in the notification body. The clock and status area are reserved. A fixed backing is drawn only after an explicit photo selection.
- **Footer.** The existing notification settings and clear buttons receive the same material on compatible backgrounds. Both their original actions and accessibility nodes are retained.

### Backdrop and optical model

Android can compose an app behind the shade in another window. In native mode LiquidShade never requests a wallpaper file, wallpaper thumbnail, screen capture or app buffer. A RenderNode in the notification background uses Android's backdrop RenderEffect when available. The GPU input is the previous content of the **same render target**. A signed-distance lens bends those pixels along its bevel, softens detail under the face and applies restrained edge dispersion. The sampler has no access to other windows. With an empty input, the transparent lens leaves the Samsung composition visible. Directional specular arcs, an internal reflection and a far-edge shadow give the glass depth even on a smooth shade. Battery Saver omits the live GPU filter. Android's own cross-window blur depends on the firmware, system settings and current app.

The optional photo mode uses one privately selected image, a bounded blur pyramid and the same mapping for card and shade. Work is cancelled by generation on mode, lock or quality changes. Samsung's screenshot notification thumbnail and album art are original foreground content. The settings preview has its own demonstration background.

Diagnostics distinguishes a submitted live filter, transparent lighting, artwork and fixed-photo refraction. A submitted filter does not establish whether Samsung exposed detailed, opaque pixels at that moment. Zero active surfaces while settings is open is normal. The per-component last draw and the peak during the last opening are more informative.

### Settings

| Category | Controls |
| --- | --- |
| General | Enable, draggable preview, Clear/Balanced/Frosted/Dark Glass/Custom, wallpaper/theme adaptation, Quality/Balanced/Battery Saver, reset |
| Optics | Refraction, blur, compression, depth, bevel, dispersion, reflections, rim, tint colour and opacity, saturation and vibrancy |
| Cards | Enable, opacity, radius, border, visual spacing, highlight colour, contrast protection, stock/joined/individual group children, press response |
| Media | Enable, blur, refraction, artwork tint, opacity, radius, stock/highlight/artwork progress colour, rim |
| Shade | Enable, optional local image, native/selected-photo shade blur, dim, tint colour and intensity, gradient, glass footer |
| Motion | Enable, amplitude, stiffness, damping, speed, follow system motion settings, replay in preview. The scoped row dispatch observer forwards press, move, release and cancellation to the material. Native expansion height determines when a row can enter. Visible rows arrive in screen order with up to 34 dp of vertical travel and a spring return. Horizontal dismissal stretches with the native swipe distance. Actual expansion state changes, swipe onset and dismissal trigger further motion. Repeated assignments of the same expansion state leave the spring alone. An optional whole-row RenderNode transform carries both foreground and background. Native translation, scale properties, listeners and hit targets stay under SystemUI control. The module yields to an existing animation matrix and removes only its own. |
| Advanced | Live compatibility, backend, protection, diagnostics, JSON import/export, confirmed SystemUI restart |

The 0–100 optics sliders map to physical ranges. Refraction displacement is up to 28 dp before the compression factor. Bevel is approximately 1.6–35 dp and capped by the half-size of the surface. Dispersion is at most 0.85 dp per side in Quality. Pane and material blur contribute up to 19 and 26 dp respectively, combined in variance. Tint layers are capped to preserve the backdrop. Quality/Balanced/Battery Saver bound the wallpaper’s long edge to 1920/1280/720 pixels. Battery Saver uses a prefiltered image, a single backdrop sample, and disables springs. Named presets adjust optical, card, media and shade values while preserving component switches, touch preferences and group layout. Colour controls accept RGB hex. French and English follow Android’s locale.

### Compatibility evidence and limits

The inspected `com.android.systemui` APK reports version 17, version code 37 and SDK 37. Its SHA-256 is `d6c164100707987a7dad8405f7c90d1c9af82b62db0444354f0decf778e8bb50`. The APK establishes the presence of these paths; it does not establish which paths are active on every One UI 9 build.

| Component | Evidence checked in Samsung DEX/resources |
| --- | --- |
| Shade | NotificationStackScrollLayout attachment, expansion, QS fraction and bar-state methods/fields; NotificationPanelView and notification stack resource |
| Notification | ActivatableNotificationView.dispatchTouchEvent(MotionEvent) returning boolean; ExpandableNotificationRow lifecycle/group/keyguard methods; authoritative ActivatableNotificationView.mOnKeyguard (the child background initializes its own flag to true); NotificationBackgroundView.mBackground and setCustomBackground(SeslRecoilDrawable); notification_material_bg, notification_focus_overlay, radial_gradient_view |
| Blur | The inspected NotificationBackgroundView.isBlurEnabled() returns false. Global hook suppression of Samsung blur would also affect other contexts and is unnecessary on this path. |
| Media | SecMediaControlPanel.attach and bind; mType, mViewHolder, playerView; ENR/OA/QS distinctions; sec_qs_media_controls, sec_album_art, shade_album_art/originImage, newImage and sec_media_progress_bar |
| Footer | sec_status_bar_notification_shelf places noti_setting and clear_all inside NotificationShelf. Both are native text controls. |

Binding requires matching runtime types, names, resource identities and attachment. The renderer restores only installations it still owns. Configuration and status IPC require the privileged SystemUI permission and check the caller UID. When enabled, LSPosed diagnostics report numeric source/backend identifiers, active opening counts, spring starts and submitted row draws. They contain no touch coordinates or notification data. Three recorded adapter errors suspend modifications until the user clears protection. This counts module-caught failures; it cannot diagnose every native crash or GPU-driver reset.

The Android instrumentation changes the real scene underneath an installed material, checks transmitted colours, compares refracted stripe positions and verifies that foreground pixels and the header stay sharp. It also drives press, release, swipe and entry, checks native click delivery, and switches artwork slots. The physical S23+, its media hierarchy, frame pacing at 120 Hz, and which pixels its compositor makes available remain unverified for this release.

## Build

Android Studio/Gradle project, Java 17, Gradle 8.11.1 (wrapper + distribution checksum), AGP 8.9.2, compile/target SDK 35, Build Tools 35.0.0. The APK’s public API calls fit SDK 35. The hook is intentionally gated to the inspected API 37 host. Maven Central supplies the real `io.github.libxposed:api:102.0.0` as a compile-only dependency.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

GitHub Actions runs the configuration tests and lint, renders the AGSL source on desktop Skia against a known image to check its pixel contract, verifies the signed APK, and uploads the APK and checksum. CI uses a dedicated LiquidShade debug-format JKS from the optional Actions secret LIQUIDSHADE_SIGNING_JKS_B64, or generates a fresh key per run if absent. The optional key uses the Gradle debug alias and passwords and must belong exclusively to LiquidShade. To move between artifacts signed by different keys, export settings, uninstall the old LiquidShade APK, install the new one, re-enable the scope and import settings. Keys from other modules are never used.

See [third-party acknowledgements](THIRD_PARTY.md).

The CI artifact also includes a two-scene PNG from the native window test.
