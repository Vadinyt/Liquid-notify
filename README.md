# LiquidNotify

Native Samsung One UI 9 notification-shade customization research project for Galaxy S23+ (SM-S916B/DS), Android 17.

**Status:** v0.16 experimental LSPosed module. GitHub Actions builds a signed APK; screenshots from the Galaxy S23+ on One UI 9 confirm visual changes. Native per-card blur and OEM background geometry require ongoing device testing.

The module targets `com.android.systemui` and must preserve Samsung notification delivery, privacy, actions and security indicators.

See [design specification](DESIGN_SPEC.md) and [Phase 1 investigation](PHASE1.md).

Initial visual direction: colorful pastel gradient glass for highlighted notifications, blue-grey translucent surfaces for ordinary notifications, with Samsung-native layout and interaction.


## Frosted glass preview

The app includes a **Frosted Glass** preset and controls for opacity (35–245), native Samsung blur (0–80 px), corner radius (18–48 dp) and three color palettes. The renderer applies tint with a single opacity pass, avoids duplicate strokes while native blur is active, and clips highlights to the Samsung background outline. Blur support varies by Samsung firmware.

See [configuration](CONFIGURATION.md) and [GitHub Actions](https://github.com/Vadinyt/Liquid-notify/actions/workflows/build.yml).


## v0.14 rendering correction

Card blur now follows native Samsung background geometry. On rows where drawable bounds diverge from the View during animations, it falls back to the default OEM surface rather than introducing squared bottoms or oversized rim arcs. See [configuration](CONFIGURATION.md).


## Liquid Glass direction (v0.15)

Not a frosted-glass imitation: native Samsung notifications now gain curved specular lens edges, internal chromatic dispersion, gradient light spill and a short entrance glint. The `Liquid reflections` slider changes the optical strength; Samsung background blur is secondary. The built-in **APPLY LIQUID GLASS** preset selects 85/255 opacity, 40 px blur, 44 dp corners and 80% reflection strength. Real wallpaper pixel refraction is not yet implemented and requires a different shader pipeline.


## v0.16 clear blue Liquid Glass

Based on the user's supplied clear iOS-style glass notification reference, the default style now uses bright pale-blue translucent cards, a fine cool-white rim and optical top highlights. The native blur is intentionally lighter. Use **APPLY CLEAR LIQUID GLASS** for opacity 104/255, blur 28 px, reflection 86/100, 44 dp radius and icy-blue palette. Real refractive wallpaper pixel distortion is not implemented; Samsung native notification interactions remain unchanged.
