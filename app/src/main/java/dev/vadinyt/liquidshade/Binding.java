package dev.vadinyt.liquidshade;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.*;
import java.lang.ref.WeakReference;

/** Owns exactly one installation, and restores it only while that same installation is present. */
final class Binding {
  final WeakReference<View> view;
  final Material.Kind kind;
  LayerDrawable layers;
  Drawable original;
  Material glass, idle;
  boolean overlay;
  private WeakReference<ProgressBar> bar;
  private ColorStateList oldTint, appliedTint;

  Binding(View view, Material.Kind kind) {
    this.view = new WeakReference<>(view);
    this.kind = kind;
  }

  boolean owns(View v) {
    if (glass == null) return false;
    if (layers != null) return layers.getDrawable(0) == glass;
    return overlay || v.getBackground() == glass;
  }

  static void background(View v, Drawable d) {
    int l = v.getPaddingLeft(),
        t = v.getPaddingTop(),
        r = v.getPaddingRight(),
        b = v.getPaddingBottom();
    v.setBackground(d);
    if (l != v.getPaddingLeft()
        || t != v.getPaddingTop()
        || r != v.getPaddingRight()
        || b != v.getPaddingBottom()) v.setPadding(l, t, r, b);
  }

  void uninstall() {
    View v = view.get();
    if (glass != null && v != null && owns(v)) {
      if (layers != null) {
        if (original instanceof GradientDrawable g && glass.getCornerRadii() != null)
          g.setCornerRadii(glass.getCornerRadii());
        layers.setDrawable(0, original);
      } else if (overlay) v.getOverlay().remove(glass);
      else background(v, original);
      v.invalidate();
    }
    if (glass != null) {
      glass.suspend();
      idle = glass;
    }
    glass = null;
    original = null;
    layers = null;
    overlay = false;
    if (bar != null && bar.get() != null && bar.get().getProgressTintList() == appliedTint)
      bar.get().setProgressTintList(oldTint);
    bar = null;
    oldTint = null;
    appliedTint = null;
  }

  void dispose() {
    uninstall();
    if (idle != null) idle.release();
    idle = null;
  }

  void setProgress(ProgressBar p, int mode, int color) {
    if (p == null) return;
    if (mode == 0) {
      if (bar != null && bar.get() == p && p.getProgressTintList() == appliedTint)
        p.setProgressTintList(oldTint);
      bar = null;
      appliedTint = null;
      return;
    }
    if (bar == null || bar.get() != p || p.getProgressTintList() != appliedTint) {
      bar = new WeakReference<>(p);
      oldTint = p.getProgressTintList();
    }
    if (appliedTint == null
        || appliedTint.getDefaultColor() != color
        || p.getProgressTintList() != appliedTint) {
      appliedTint = ColorStateList.valueOf(color);
      p.setProgressTintList(appliedTint);
    }
  }

  private WeakReference<Drawable> artIdentity;
  private int artColor;

  boolean artChanged(ImageView v) {
    return artIdentity == null || artIdentity.get() != v.getDrawable();
  }

  int art(ImageView image) {
    if (!artChanged(image)) return artColor;
    Drawable drawable = image.getDrawable();
    artIdentity = new WeakReference<>(drawable);
    artColor = 0;
    for (int i = 0;
        i < 4 && drawable instanceof LayerDrawable layer && layer.getNumberOfLayers() > 0;
        i++) drawable = layer.getDrawable(layer.getNumberOfLayers() - 1);
    if (drawable instanceof ColorDrawable color) return artColor = color.getColor();
    if (!(drawable instanceof BitmapDrawable d)) return 0;
    Bitmap b = d.getBitmap();
    if (b == null || b.isRecycled()) return 0;
    if (b.getConfig() == Bitmap.Config.HARDWARE) {
      try {
        b = Bitmap.createScaledBitmap(b, 3, 3, true).copy(Bitmap.Config.ARGB_8888, false);
      } catch (RuntimeException | OutOfMemoryError e) {
        return 0;
      }
      if (b == null) return 0;
    }
    int red = 0, green = 0, blue = 0;
    for (int x = 1; x <= 3; x++)
      for (int y = 1; y <= 3; y++) {
        int c = b.getPixel(b.getWidth() * x / 4, b.getHeight() * y / 4);
        red += Color.red(c);
        green += Color.green(c);
        blue += Color.blue(c);
      }
    return artColor = Color.rgb(red / 9, green / 9, blue / 9);
  }
}
