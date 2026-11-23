"""Portable pixel contract for the exact AGSL source, executed with skia-python 144.1."""
from pathlib import Path
import numpy as np
import skia

root = Path(__file__).resolve().parents[1]
program = next(root.rglob("LensProgram.java")).read_text().split('"""')[1]
effect = skia.RuntimeEffect.MakeForShader(program)
pixels = np.full((160, 240, 4), 255, dtype=np.uint8)
x = np.arange(240)
pixels[:, :, 0] = (x * 11) % 256
pixels[:, :, 1] = (x * 8) % 256
pixels[:, :, 2] = 90
image = skia.Image.fromarray(pixels, colorType=skia.ColorType.kRGBA_8888_ColorType)


def render(shift, origin=(0, 0), dispersion=0.0, scale=1.0, overrides=None, child=image):
    build = skia.RuntimeShaderBuilder(effect)
    build.setChild("src", child.makeShader())
    build.setChild("clearSrc", child.makeShader())
    build.setChild("coarseSrc", child.makeShader())
    uniforms = {
        "extent": (85, 48), "center": (120, 80), "mapX": (scale, 0, origin[0]),
        "mapY": (0, scale, origin[1]), "mapW": (0, 0, 1),
        "frame": (240, 160), "texture": (240, 160), "radii": (18, 18),
        "optic": (shift, 14, .6, 0), "finish": (dispersion, 0, 0, 0),
        "tone": (1, 0, 0, 0), "accent": (1, 1, 1, 1), "shade": (0, 0, 0, 0),
        "wash": (0, 0, 0, 0), "flags": (1, 1, 1, .94), "detail": (1, 0, 0, 0),
        "fullSize": (240, 160), "surface": (-.45, -.5, 1, 1), "artUV": (1, 1, 0, 0),
    }
    uniforms.update(overrides or {})
    for key, value in uniforms.items():
        build.setUniform(key, list(value))
    surface = skia.Surface(240, 160)
    surface.getCanvas().drawPaint(skia.Paint(Shader=build.makeShader()))
    return surface.makeImageSnapshot().toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)


flat, lens = render(0), render(12)
assert np.array_equal(flat[80, 120], lens[80, 120]), "flat centre changed"
assert np.array_equal(flat[80, 120], pixels[80, 120]), "wallpaper coordinate mismatch"
assert np.array_equal(render(12, (7, 0))[80, 120], pixels[80, 127]), "moving lens lost alignment"
assert np.array_equal(render(0, scale=.5)[80, 120], pixels[40, 60]), "scaled lens lost alignment"
assert lens[32, 35, 3] == 0, "rounded corner escaped its mask"
assert np.abs(flat[80, 201, :3].astype(int) - lens[80, 201, :3]).max() > 8, "no displacement at bevel"
assert np.any(render(12, dispersion=1.5)[:, :, 0] != lens[:, :, 0]), "dispersion has no effect"
print("PASS: centre identity, window coordinates, rounded mask, edge displacement, dispersion")

# The untinted transparent backend must never reveal either sampled child.
glass = {"surface": (-.55, -.4, 0, 1), "finish": (.5, 2, .75, .75),
         "tone": (1, .12, .12, 0), "accent": (.65, .8, .9, 1)}
white = skia.Image.fromarray(np.full_like(pixels, 255), colorType=skia.ColorType.kRGBA_8888_ColorType)
rest = render(12, overrides=glass)
assert np.array_equal(rest, render(12, overrides=glass, child=white)), "transparent backend samples an image"
assert 0 < rest[80, 120, 3] < 80, "missing backdrop should stay clear"
for z in (0, 1):
    base = dict(glass, surface=(-.55, -.4, z, 1))
    idle = render(12, overrides=base)
    held = render(12, overrides=dict(base, detail=(1, 0, .95, 0)))
    delta = abs(held.astype(int) - idle.astype(int)).max(axis=2)
    assert (delta > 8).sum() > 300, f"spring is imperceptible on backend {z}"
print("PASS: transparent fallback, source independence, visible press on both backends")

for name, changed in {
    "refraction": {"optic": (23, 14, .6, 0)}, "compression": {"optic": (12, 14, 1, 0)},
    "bevel": {"optic": (12, 26, .6, 0)}, "blur": {"optic": (12, 14, .6, 11)},
    "reflection": {"finish": (0, 1, 0, 1)}, "rim": {"finish": (0, 3, 1, 0)},
    "tint": {"tone": (1, 0, .4, 0)}, "saturation": {"tone": (.5, 0, 0, 0)},
    "vibrancy": {"tone": (1, .45, 0, 0)}, "corner radius": {"radii": (38, 38)},
}.items():
    out = render(12, overrides=changed)
    delta = abs(out.astype(int) - lens.astype(int)).max(axis=2)
    assert (delta > 3).sum() > 55, f"{name} made no visible difference"

# With an edge-only media layer the original player interior is fully exposed.
art = {"surface": (0, 0, 2, 1), "artUV": (.5, .5, 7, 11), "detail": (1, 14, 0, 0)}
assert render(12, overrides=art)[80, 120, 3] == 0, "overlay painted the album/controls in the centre"
assert np.array_equal(render(0, overrides=dict(art, detail=(1, 0, 0, 0)))[80, 120],
                      pixels[51, 67]), "album crop misaligned"
print("PASS: ten independent material controls, album mapping, untouched media centre")

# A held press deforms the contacted edge locally even with all lighting disabled.
geometry = {"surface": (.96, 0, 0, 2), "tone": (1, 0, 1, 0), "finish": (0, 0, 0, 0)}
idle_mask = render(12, overrides=geometry)[:, :, 3]
held_mask = render(12, overrides=dict(geometry, detail=(1, 0, 1, 0)))[:, :, 3]
assert idle_mask[80, 203] > 200 and held_mask[80, 203] < 20
assert idle_mask[80, 36] == held_mask[80, 36]
print("PASS: local pressure deforms the contacted edge and preserves the opposite edge")

# A uniform dark shade exposes the fallback bevel without needing wallpaper pixels.
mat = render(0, overrides={"surface": (-.55, -.4, 0, 2), "finish": (0, 2, .55, .36),
                            "tone": (1, 0, .12, 0), "accent": (.87, .94, .97, 1)})
alpha = mat[:, :, 3:4].astype(float) / 255
composited = mat[:, :, :3] * alpha + (1 - alpha) * [16, 23, 36]
assert np.linalg.norm(composited[36, 120]-composited[80, 120]) > 15, "bevel disappears on a dark native shade"
print("PASS: internal bevel is visible above a uniform dark system backdrop")

# Unlike an image-backed lens, native compositor mode cannot displace inaccessible pixels.
# Its optically shaped edge must nevertheless respond to the refraction slider.
native = {
    "surface": (-.55, -.4, 0, 2),
    "finish": (0, 1.5, .65, .65),
    "tone": (1, .1, .12, 0),
    "accent": (.88, .95, 1, 1)
}
weak = render(0, overrides=dict(native, optic=(0, 12, .65, 0)))
strong = render(0, overrides=dict(native, optic=(36, 12, .65, 0)))
native_difference = np.abs(weak.astype(int) - strong.astype(int)).max(axis=2)
assert (native_difference > 3).sum() > 50, "native fallback refraction control became inert"
bare = render(0, overrides=dict(native, tone=(1,.1,.05,0)))
frost = render(0, overrides=dict(native, tone=(1,.1,.20,0)))
assert frost[80,120,3] > bare[80,120,3] + 20, "native frosted face became inert"
print("PASS: native no-source refraction contour and translucent frost are responsive")

# The old full-window shader covered headers from other siblings. Check alpha on every
# column above the native header boundary, including a shaded / dimmed input.
body = {"extent": (120, 80), "radii": (0, 0), "body": (41, 16, 1),
        "wash": (.17, .09, 0, 0), "shade": (.2, .5, .8, .2)}
underlay = render(0, overrides=body)
assert np.all(underlay[:41,:,3] == 0)
assert 40 < underlay[48,100,3] < 180
assert np.all(underlay[62:120,:,3] == 255)
print("PASS: untouched native header, soft transition, fully drawn shade below it")

# Thin, directional specular arcs and local contrast on the textured notification face.
def solid(rgb):
    p = np.empty_like(pixels)
    p[:] = (*rgb, 255)
    return skia.Image.fromarray(p, colorType=skia.ColorType.kRGBA_8888_ColorType)

midnight = solid((16, 25, 45))
bright = solid((240, 234, 230))
settings = {"finish": (0, 1.2, .82, .48), "face": (1, 1), "tone": (1, 0, 0, 0)}
arcs = render(14, overrides=settings, child=midnight).astype(float)
# Within the straight edge, the top-left glint is stronger than the top-right.
assert arcs[32,79,0] > arcs[32,166,0]+18, "upper glint doesn't taper"
assert arcs[32,79,0] > arcs[38,79,0]+32, "broad painted ridge"
contrast = dict(settings, tone=(1,0,0,1))
lit = render(14, overrides=contrast, child=bright)
dark = render(14, overrides=contrast, child=midnight)
assert lit[80,120,0] < 160 and abs(int(dark[80,120,0])-16) < 4, "white-text contrast washed out dark pixels"
light_ink = render(14, overrides=dict(contrast, wash=(0,0,1,0)), child=midnight)
assert light_ink[80,120,0]>100, "dark-text contrast missing"
print("PASS: directional specular taper, thin rim, local contrast for both text colours")

# The actual framebuffer-input shader must preserve a transparent target and respond to a fresh scene.
live_src = next(root.rglob("LiveBackdrop.java")).read_text().split('"""')[1]
live_effect = skia.RuntimeEffect.MakeForShader(live_src)
def live(scene, displacement):
    build = skia.RuntimeShaderBuilder(live_effect)
    build.setChild("scene", scene.makeShader())
    for name, val in {"size": (240,160), "radii": (24,24), "optics": (displacement,24,.72,0),
                      "finish": (0,1,0,0)}.items():
        build.setUniform(name, list(val))
    dst = skia.Surface(240,160)
    dst.getCanvas().drawPaint(skia.Paint(Shader=build.makeShader()))
    return dst.makeImageSnapshot().toarray(colorType=skia.ColorType.kRGBA_8888_ColorType)
native0, native1 = live(image,0), live(image,24)
assert np.array_equal(native0[80,120],native1[80,120])
assert np.max(abs(native0[80,3,:3].astype(int)-native1[80,3,:3].astype(int)))>24
empty = skia.Image.fromarray(np.zeros_like(pixels),colorType=skia.ColorType.kRGBA_8888_ColorType)
assert np.max(live(empty,24))==0
print("PASS: live-layer shader transmits transparent input, preserves the centre and bends scene detail")
