# LiquidNotify v0.15.0: Liquid Glass optical surfaces

LiquidNotify modifies Samsung's existing notification background views in `com.android.systemui`. It does not replace the notification center or intercept notifications, gestures, expansion, or dismissal.

**New experimental effect:** tries Samsung's `View.semSetBlurInfo(SemBlurInfo)` with a rounded native blur region for each notification, and adds subtle *in-bounds* sheen and rim rendering directly in the notification background's draw pass. This OEM API was reported on One UI 8.5, but is **not yet verified on Galaxy S23+ One UI 9 / Android 17**. When unavailable, blur is skipped and the tinted cards remain.

## Controls

Open the LiquidNotify launcher application and allow root in KernelSU. Values are saved in `Settings.Global` and read by SystemUI.

| Setting | Internal key | Range | Default |
| --- | --- | --- | --- |
| Enable styling | `liquidnotify_enabled` | 0 or 1 | 1 |
| Card opacity | `liquidnotify_opacity` | 35–245 (higher is more opaque) | 85 |
| Backdrop blur (secondary) | `liquidnotify_blur_px` | 0–80 pixels, 0 disables blur | 40 |
| Card radius | `liquidnotify_radius_dp` | 18–48 dp | 44 |
| Liquid reflection intensity | `liquidnotify_liquid_strength` | 0–100 (0 disables optical effects) | 80 |
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

## v0.12 consistency fixes

- All native notification background drawables, including the dimmed layer, receive a clipped palette wash; the dimmed layer omits blur and highlight edges to avoid overlapping arcs.
- Palette colors are drawn on every background redraw rather than relying only on Samsung invoking `setTint` on settings changes.
- A SystemUI ContentObserver listens for LiquidNotify settings and invalidates tracked notification background views immediately, without requiring a restart for ordinary adjustments.
- Strongly distinct icy blue, graphite, and violet washes remain translucent and are limited to background views; notification text and icons stay untouched.
- Actual device testing remains necessary to verify all One UI 9 row states and expanded notifications.

## v0.13 opacity pipeline

- The opacity slider now composites the complete Samsung notification background on a single alpha layer during `NotificationBackgroundView.onDraw`, without reducing text/icon alpha.
- Gradient paint and rim alpha are scaled to the same slider value, rather than remaining fixed.
- Native Samsung blur's background tint is recalculated with palette and opacity, and blur information is refreshed when the tint changes.
- Setting opacity to 35 should produce a substantially lighter/more transparent surface than 245 on One UI 9. The native blur region remains beneath the background drawable.
- `saveLayerAlpha` can add GPU compositing work; performance on-device needs verification. If SystemUI slows down, disable LiquidNotify in LSPosed and restart.

## v0.14: native blur geometry stability

Samsung's NotificationBackgroundView changes mActualHeight and its drawable bounds independently from the View dimensions during expansions, transitions, and clipping. The previous v0.11 ViewOutlineProvider + clipToOutline was clipping the render node and causing squared-off notification bottoms and purple crescents.

This version removes the custom View outline entirely. Native SemBlurInfo blur is only applied when the OEM background drawable fills the measured View with no clip top/bottom and no expansion animation. When the bounds disagree, it removes native blur and lets Samsung render the row normally, keeping the tint and opacity effects. Decorative highlight painting is also skipped on transient mismatched geometry. No other applications or keyboard hooks are changed.

**Trade-off:** Individual native blur is intentionally absent during some expanded or clipped notification states, because these cannot be safely rounded independently with the current OEM API. Future changes should use device-specific SystemUI class evidence rather than applying an extra global outline.

## v0.15: Liquid Glass, not Frosted Glass

LiquidNotify now draws **liquid optical effects** inside each Samsung notification card: a curved bright rim, a secondary inner lens edge, subtle cyan/violet dispersion, diagonal reflected light, and a short specular shimmer when a card first appears or the reflection strength changes. The movement runs for 720 ms only and respects Android's animation-duration setting. No permanent redraw loop or replacement notification UI.

The prior Frosted Glass preset is replaced by **APPLY LIQUID GLASS**, using opacity 85, Samsung blur 40 px, 44 dp radius and reflection intensity 80/100. The blur remains a secondary supporting effect. The app and root settings now expose `liquidnotify_liquid_strength` (0–100).

**Rendering limitation:** This is a lensing *simulation*, not true optical refraction of the wallpaper; that would require sampling the background using an OEM GPU shader. Native content, text, notification actions, and animations remain Samsung-owned. Some nonstandard, clipped OEM states still omit additional optical effects to preserve correct geometry.
