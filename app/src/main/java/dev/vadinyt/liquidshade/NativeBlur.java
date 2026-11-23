package dev.vadinyt.liquidshade;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Method;

/** A compositor blur region attached to the shade backing only when the shader has no image. */
final class NativeBlur {
  private Drawable region;
  private Method radius;
  private boolean attempted;
  private int lastRadius = -1;

  boolean draw(View owner, Canvas canvas, Rect bounds, int pixels) {
    if (pixels <= 0) {
      suspend();
      return false;
    }
    if (!canvas.isHardwareAccelerated()) return false;
    try {
      if (!attempted) {
        attempted = true;
        WindowManager wm = owner.getContext().getSystemService(WindowManager.class);
        if (wm == null || !wm.isCrossWindowBlurEnabled()) return false;
        Object root = View.class.getMethod("getViewRootImpl").invoke(owner);
        if (root == null) return false;
        Object blur = root.getClass().getMethod("createBackgroundBlurDrawable").invoke(root);
        if (!(blur instanceof Drawable drawable)
            || !blur.getClass()
                .getName()
                .equals("com.android.internal.graphics.drawable.BackgroundBlurDrawable"))
          return false;
        Method size = blur.getClass().getMethod("setBlurRadius", int.class);
        Method color = blur.getClass().getMethod("setColor", int.class);
        color.invoke(blur, 0);
        radius = size;
        region = drawable;
      }
      if (region == null) return false;
      if (lastRadius != pixels) {
        radius.invoke(region, pixels);
        lastRadius = pixels;
      }
      region.setBounds(bounds);
      region.draw(canvas);
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
      clear();
      attempted = true;
      return false;
    }
  }

  void clear() {
    if (region != null)
      try {
        radius.invoke(region, 0);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
    region = null;
    radius = null;
    lastRadius = -1;
    attempted = false;
  }

  void suspend() {
    if (region != null) clear();
  }
}
