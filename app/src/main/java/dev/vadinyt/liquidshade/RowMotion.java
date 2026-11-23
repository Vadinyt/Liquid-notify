package dev.vadinyt.liquidshade;

import android.graphics.*;
import android.view.View;
import java.lang.ref.WeakReference;

/** A temporary RenderNode animation matrix alongside Samsung's own layout and swipe translation. */
final class RowMotion {
  private final WeakReference<View> row;
  private final RowPose pose = new RowPose();
  private final Matrix local = new Matrix(),
      applied = new Matrix(),
      inverse = new Matrix(),
      intermediate = new Matrix(),
      global = new Matrix();
  private boolean owned, yielded;

  RowMotion(View v) {
    row = new WeakReference<>(v);
  }

  void begin() {
    if (!owned) yielded = false;
  }

  boolean frame(
      View bg,
      Rect bounds,
      float value,
      float amount,
      float x,
      float y,
      boolean entering,
      float swipe) {
    View v = row.get();
    if (v == null
        || !v.isAttachedToWindow()
        || bg.getParent() != v
        || !v.isShown()
        || !v.isHardwareAccelerated()) {
      clear();
      return false;
    }
    if (yielded) return false;
    Matrix current = v.getAnimationMatrix();
    if (v.getAnimation() != null || (owned ? !applied.equals(current) : current != null)) {
      clear();
      yielded = true;
      return false;
    }
    if ((Math.abs(value) + Math.abs(swipe)) * amount < .002f) {
      clear();
      return false;
    }
    int w = bounds.isEmpty() ? bg.getWidth() : bounds.width(),
        h = bounds.isEmpty() ? bg.getHeight() : bounds.height();
    if (w <= 0 || h <= 0) return false;
    pose.entering = entering;
    pose.swipe = swipe;
    pose.set(
        bg.getLeft() + bounds.left,
        bg.getTop() + bounds.top,
        w,
        h,
        bg.getResources().getDisplayMetrics().density,
        value,
        amount,
        x,
        y);
    local.setScale(pose.sx, pose.sy, pose.px, pose.py);
    local.postTranslate(pose.dx, pose.dy);
    // RenderNode composes animationMatrix before its regular view transform.
    Matrix nativeMatrix = v.getMatrix();
    if (!nativeMatrix.invert(inverse)) {
      clear();
      return false;
    }
    intermediate.setConcat(local, inverse);
    applied.setConcat(nativeMatrix, intermediate);
    v.setAnimationMatrix(applied);
    owned = true;
    return true;
  }

  /**
   * View.transformMatrixToGlobal includes property transforms but excludes the animation matrix.
   */
  void mapToWindow(Matrix childToWindow) {
    View v = row.get();
    if (!owned || v == null) return;
    global.reset();
    v.transformMatrixToGlobal(global);
    if (global.invert(inverse)) {
      intermediate.setConcat(local, inverse);
      inverse.setConcat(global, intermediate);
      childToWindow.postConcat(inverse);
    }
  }

  void clear() {
    View v = row.get();
    if (owned && v != null && applied.equals(v.getAnimationMatrix())) v.setAnimationMatrix(null);
    owned = false;
  }
}
