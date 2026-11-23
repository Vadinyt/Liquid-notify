package dev.vadinyt.liquidshade;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Method;

/**
 * Uses SurfaceFlinger's cross-window blur regions rather than a cached wallpaper bitmap.
 * The platform implementation is hidden and Samsung may replace it: every reflection is
 * guarded, all failures leave the original One UI drawable in control. No screen capture.
 *
 * The compositor, not this Java callback, decides if a submitted region is actually blurred.
 */
final class CompositorBlur {
  private Drawable region;
  private Method setBlurRadius, setCorners;
  private boolean failed;
  private int lastRadius = -1;
  private final Rect previous = new Rect();
  private float previousTop = -1, previousBottom = -1;

  static boolean enabled(View view) {
    if (view == null) return false;
    try {
      WindowManager manager = view.getContext().getSystemService(WindowManager.class);
      return manager != null && manager.isCrossWindowBlurEnabled();
    } catch (RuntimeException | LinkageError unavailable) {
      return false;
    }
  }

  private boolean initialize(View view) throws ReflectiveOperationException {
    Method getRoot = View.class.getDeclaredMethod("getViewRootImpl");
    getRoot.setAccessible(true);
    Object root = getRoot.invoke(view);
    if (root == null) return false;
    Method create = root.getClass().getMethod("createBackgroundBlurDrawable");
    create.setAccessible(true);
    Object drawable = create.invoke(root);
    if (!(drawable instanceof Drawable result)) return false;
    Method radius = drawable.getClass().getMethod("setBlurRadius", int.class);
    Method corners = drawable.getClass().getMethod(
        "setCornerRadius", float.class, float.class, float.class, float.class);
    region = result;
    setBlurRadius = radius;
    setCorners = corners;
    region.setAlpha(255);
    return true;
  }

  boolean draw(View host, Canvas canvas, RectF area, float top, float bottom, int radius) {
    if (failed || host == null || !canvas.isHardwareAccelerated()
        || radius <= 0 || area.isEmpty() || !enabled(host)) {
      stop();
      return false;
    }
    try {
      if (region == null && !initialize(host)) return false;
      if (lastRadius != radius) {
        setBlurRadius.invoke(region, radius);
        lastRadius = radius;
      }
      Rect bounds = new Rect(
          Math.round(area.left), Math.round(area.top),
          Math.round(area.right), Math.round(area.bottom));
      if (!previous.equals(bounds)) {
        region.setBounds(bounds);
        previous.set(bounds);
      }
      // BackgroundBlurDrawable's corner order is TL, TR, BL, BR.
      if (previousTop != top || previousBottom != bottom) {
        setCorners.invoke(region, top, top, bottom, bottom);
        previousTop = top;
        previousBottom = bottom;
      }
      // Its internal transparent SRC path clears only this rounded region of the shade
      // surface. SurfaceFlinger then blends the blurred content behind the window here.
      region.draw(canvas);
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError e) {
      failed = true;
      stop();
      return false;
    }
  }

  void stop() {
    if (region != null && lastRadius > 0) {
      try {
        setBlurRadius.invoke(region, 0);
        region.setAlpha(0);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      }
    }
    lastRadius = 0;
    previous.setEmpty();
    previousTop = previousBottom = -1;
  }

  void release() {
    stop();
    region = null;
    setBlurRadius = null;
    setCorners = null;
    failed = false;
  }
}
