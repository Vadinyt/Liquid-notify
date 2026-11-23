package dev.vadinyt.liquidshade;

/**
 * Short contact/reveal deformation in row-local space. Native layout and hit regions stay intact.
 */
final class RowPose {
  float sx = 1, sy = 1, px, py, dx, dy;

  void set(
      float left,
      float top,
      float w,
      float h,
      float dp,
      float spring,
      float amount,
      float x,
      float y) {
    float n = Math.max(-.5f, Math.min(1.25f, spring)) * amount;
    sx = 1 - .057f * n;
    sy = 1 - .083f * n;
    px = left + w * (.5f + .17f * x);
    py = top + h * (.5f + .15f * y);
    dx = dp * 2.5f * x * n;
    dy = dp * 5 * n;
  }
}
