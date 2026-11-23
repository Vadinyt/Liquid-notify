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


def render(shift, origin=(0, 0), dispersion=0.0, scale=1.0):
    build = skia.RuntimeShaderBuilder(effect)
    build.setChild("src", image.makeShader())
    for key, value in {
        "extent": (85, 48), "center": (120, 80), "mapX": (scale, 0, origin[0]),
        "mapY": (0, scale, origin[1]), "mapW": (0, 0, 1),
        "frame": (240, 160), "texture": (240, 160), "radii": (18, 18),
        "optic": (shift, 14, .6, 0), "finish": (dispersion, 0, 0, 0),
        "tone": (1, 0, 0, 0), "accent": (1, 1, 1, 1), "shade": (0, 0, 0, 0),
        "wash": (0, 0, 0, 0), "flags": (1, 1, 1, 0), "detail": (1, 0, 0, 0),
    }.items():
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
