package dev.vadinyt.liquidshade;

/**
 * Short contact/reveal deformation in row-local space. Native layout and hit regions stay intact.
 */
final class RowPose {
  boolean entering;
  float swipe;
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
    sx = 1 - (entering ? .072f : .087f) * n;
    sy = 1 - (entering ? .13f : .105f) * n;
    px = left + w * (.5f + .17f * x);
    py = top + h * (.5f + .15f * y);
    dx = dp * 2.5f * x * n;
    // The opening travels with the list. Limit translation for very short grouped children.
    float pull = swipe * amount;
    sx += .052f * Math.abs(pull);
    sy -= .045f * Math.abs(pull);
    dx += dp * 3 * pull;
    dy = (entering ? Math.min(34 * dp, .48f * h) : dp * 5) * n;
  }
}
