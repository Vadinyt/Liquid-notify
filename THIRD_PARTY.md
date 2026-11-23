# Third-party references

The Gradle wrapper scripts and wrapper JAR come from Gradle 8.11.1, Apache License 2.0. The corresponding license and notice are in `licenses/`.

The project compiles against io.github.libxposed:api:102.0.0 from Maven Central. LSPosed provides those classes on the device. No API classes are packaged in the application.
https://github.com/libxposed/api/tree/102.0.0

The aligned-image demonstration and shape-dependent displacement at https://liquidglass.dev/ informed the optical model. The renderer and material preview in this project are original.

https://github.com/AurelioMo/glass-ui publishes a README and visual assets. It documents refractive wallpaper cards, Samsung blur handling, stacked groups and live settings. Its public tree contained no Android source when inspected. No Glass UI code or images are distributed here.

https://github.com/QWEA0/Liquid-Glass-Android demonstrates SDF lenses and view-tree backdrop capture. This implementation uses its own shader and a deliberately narrower input restricted to the unlocked shade backdrop and compatible album artwork. No library code, demo images, or screenshots are redistributed.

The notification examples on https://liquidglassdesign.com/ informed the directional bevel and contact lighting in 1.1. No gallery images or code are included. Samsung’s published framework mirror at https://github.com/488315/samsung_framework was consulted for wallpaper type detection and verification of home-scoped provider frame and thumbnail methods. Android RenderNode and ImageDecoder sources were consulted for matrix order and non-seekable input handling.

Samsung SystemUI was inspected to identify methods, types and resource names for interoperability. No Samsung APK, DEX, XML, artwork, user screenshots, or notification data is included.
