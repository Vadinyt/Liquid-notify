# LiquidNotify v0.18.0: Single translucent glass pane

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

The app provides a **Clear Liquid Glass** preset; the previous frosted preset is historical. The native tint remains fully opaque; opacity is applied once to Samsung's drawable, correcting earlier double multiplication of alpha. A single clipped inner sheen is used; the extra stroke is omitted when native blur is active.

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

## v0.16 reference-based clear lens

Target: the user-supplied iOS-style light-blue translucent notification (15 Nov 2026) with a fine white top-edge and no dark matte panel.

- The Samsung tint is now **pale sky blue**, pale graphite or pearl lavender, instead of dark blue-gray. The same palette is applied on live updates and normal OEM row rebinds.
- The native Samsung blur's background color is brighter and less opaque. Blur remains secondary to the material itself.
- A *material* top-to-bottom glaze, two internal lens rims, cyan edge spill and a small time-limited reflection replace the previous stronger blue/violet decorative overlay.
- Preset **APPLY CLEAR LIQUID GLASS** saves palette icy blue, opacity 104, blur 28 px, reflection intensity 86, and 44 dp radius.
- Real wallpaper-pixel refraction remains unimplemented; this is an optical simulation using native Samsung backgrounds. On a dark wallpaper, the result must still be darker than the bright-sky reference because no wallpaper pixel sampling is performed.

Please compare compact and expanded notifications, palette switching and low versus high opacity, and verify root. The signed GitHub Actions APK is intended to install over the previous release.

## v0.17: Remove palettes and make glass substantially more transparent

The Color Palette controls have been removed completely from the app and runtime. Previous `liquidnotify_palette` values are ignored and no new palette keys are saved. A single neutral ice-clear material is applied to all notifications.

The OEM card tint now uses a deliberately low alpha **in addition** to the user-selected full-background alpha layer. This specifically reduces the heavy blue-gray fill seen in v0.16. A pale glint replaces the extra full-card colored wash. Native Samsung blur tint is also reduced to a near-transparent level, while the optical rim and reflection remain bright. Opacity is now expected to visibly affect the card surface, but brightness and colors still depend on Samsung's already-dimmed shade background.

**New preset:** opacity 76/255, blur 22 px, liquid reflections 90/100, corner radius 44 dp. No palette selection. True framebuffer pixel refraction is not implemented.

## v0.18 one-material composition and optical edge cleanup

Testing v0.17 on the S23+ showed a conspicuous dark gray face and two metallic white borders, neither matching the reference iOS glass sample. v0.18 retains only 23% of the user-configured opacity for the original One UI background, eliminates the normal-view secondary sheen fill, sets the SemBlurInfo background tint to transparent and draws **one unified pale ice-blue translucent pane** underneath the existing Samsung notification text and icons. The opacity slider controls that surface separately from a very faint OEM layer.

The LiquidOptics outer rim is now single and finer; the second interior outline and unconstrained upper/lower curved strokes are removed, preventing bright corner scratches. The color wash is deliberately brighter and smoothly distributed across the entire card rather than making the top appear opaque and the bottom dark. Motion remains a brief glint, no continuous redraw. Reflection intensity 0 disables reflections but keeps material visible.

**Limit:** The actual background visible through the glass is the One UI notification shade, which is dark and dimmed on the user's wallpaper. This module does not capture or refract the wallpaper behind the shade. The reference image contains bright blue sky; the same surface over a dark backdrop will inevitably look darker. Avoid broad shade/scrim hooks because they can affect unrelated system UI and lockscreen rendering.
