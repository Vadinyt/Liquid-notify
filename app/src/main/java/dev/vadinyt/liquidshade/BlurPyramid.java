package dev.vadinyt.liquidshade;

import android.graphics.*;
import java.nio.IntBuffer;

/** Low-pass wallpaper levels in premultiplied sRGB. Generated once per decoded backdrop. */
final class BlurPyramid {
  private BlurPyramid() {}

  static Bitmap[] build(Bitmap full) {
    if (full == null) return new Bitmap[0];
    Bitmap[] levels = new Bitmap[7];
    levels[0] = full;
    int[] pixels = null, scratch = null;
    Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    Rect bounds = new Rect();
    for (int i = 1; i < levels.length; i++) {
      Bitmap input = levels[i - 1];
      int w = Math.max(1, input.getWidth() / 2), h = Math.max(1, input.getHeight() / 2);
      if (w == input.getWidth() && h == input.getHeight()) {
        levels[i] = input;
        continue;
      }
      Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      bounds.set(0, 0, w, h);
      new Canvas(out).drawBitmap(input, null, bounds, filter);
      int stride = out.getRowBytes() / 4;
      if (pixels == null || pixels.length < stride * h) {
        pixels = new int[stride * h];
        scratch = new int[stride * h];
      }
      // Copy raw premultiplied lanes. Filtering all four identically keeps translucent edges clean.
      IntBuffer buffer = IntBuffer.wrap(pixels);
      out.copyPixelsToBuffer(buffer);
      for (int y = 0; y < h; y++) {
        int row = y * stride;
        for (int x = 0; x < w; x++) {
          scratch[row + x] =
              binomial(
                  pixels[row + Math.max(0, x - 2)],
                  pixels[row + Math.max(0, x - 1)],
                  pixels[row + x],
                  pixels[row + Math.min(w - 1, x + 1)],
                  pixels[row + Math.min(w - 1, x + 2)]);
        }
      }
      for (int y = 0; y < h; y++) {
        int a = Math.max(0, y - 2) * stride,
            b = Math.max(0, y - 1) * stride,
            c = y * stride,
            d = Math.min(h - 1, y + 1) * stride,
            e = Math.min(h - 1, y + 2) * stride;
        for (int x = 0; x < w; x++)
          pixels[c + x] =
              binomial(
                  scratch[a + x], scratch[b + x], scratch[c + x], scratch[d + x], scratch[e + x]);
      }
      buffer.rewind();
      out.copyPixelsFromBuffer(buffer);
      levels[i] = out;
    }
    return levels;
  }

  // Two independent 8-bit channels fit into each pair of 16-bit accumulator lanes.
  private static int binomial(int a, int b, int c, int d, int e) {
    return lanes(a, b, c, d, e) | (lanes(a >>> 8, b >>> 8, c >>> 8, d >>> 8, e >>> 8) << 8);
  }

  private static int lanes(int a, int b, int c, int d, int e) {
    int mask = 0x00ff00ff;
    return (((a & mask)
                + 4 * (b & mask)
                + 6 * (c & mask)
                + 4 * (d & mask)
                + (e & mask)
                + 0x00080008)
            >>> 4)
        & mask;
  }

  static final class Sample {
    Bitmap fine, coarse;
    float mix;

    void select(Bitmap[] levels, float sigma, int width, int height, boolean economy) {
      fine = coarse = levels.length == 0 ? null : levels[0];
      mix = 0;
      if (fine == null || sigma <= 0) return;
      float step = Math.max(width / (float) fine.getWidth(), height / (float) fine.getHeight());
      float previous = 0, target = sigma * sigma;
      for (int i = 1; i < levels.length; i++) {
        // Half-size linear resampling adds 0.25 input-pixel variance. The 5-tap binomial
        // adds one output-pixel variance, or four input-pixel variances.
        float next = previous + 4.25f * step * step;
        coarse = levels[i];
        if (target < next && next > previous) {
          mix = (target - previous) / (next - previous);
          if (economy) {
            if (mix >= .5f) fine = coarse;
            coarse = fine;
            mix = 0;
          }
          return;
        }
        fine = coarse;
        previous = next;
        step *= 2;
      }
    }
  }
}
