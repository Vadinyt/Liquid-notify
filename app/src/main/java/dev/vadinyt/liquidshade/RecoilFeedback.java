package dev.vadinyt.liquidshade;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;

/**
 * Samsung writes state colours and SRC filters to layer 1. Keep those writes on the original
 * drawable for native interactions and restoration, but reserve idle pixels for the glass.
 */
final class RecoilFeedback extends GradientDrawable {
  final GradientDrawable original;
  private boolean active;

  RecoilFeedback(GradientDrawable original) {
    this.original = original;
    setBounds(original.getBounds());
    setState(original.getState());
    setLevel(original.getLevel());
  }

  @Override
  public void draw(Canvas canvas) {
    if (active) original.draw(canvas);
  }

  @Override
  public boolean isStateful() {
    return true;
  }

  @Override
  protected boolean onStateChange(int[] states) {
    boolean show = false;
    for (int s : states)
      if (s == android.R.attr.state_pressed
          || s == android.R.attr.state_focused
          || s == android.R.attr.state_hovered
          || s == android.R.attr.state_selected
          || s == android.R.attr.state_activated) show = true;
    boolean changed = active != show;
    active = show;
    if (original != null) changed |= original.setState(states);
    if (changed) invalidateSelf();
    return changed;
  }

  @Override
  protected void onBoundsChange(Rect r) {
    if (original != null) original.setBounds(r);
  }

  @Override
  protected boolean onLevelChange(int level) {
    return original != null && original.setLevel(level);
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    if (original != null) original.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public void setTintList(ColorStateList tint) {
    if (original != null) original.setTintList(tint);
    invalidateSelf();
  }

  @Override
  public void setTintBlendMode(android.graphics.BlendMode mode) {
    if (original != null) original.setTintBlendMode(mode);
    invalidateSelf();
  }

  @Override
  public void setColor(ColorStateList color) {
    if (original != null) original.setColor(color);
    invalidateSelf();
  }

  @Override
  public void setColor(int color) {
    if (original != null) original.setColor(color);
    invalidateSelf();
  }

  @Override
  public void setAlpha(int alpha) {
    if (original != null) original.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public int getAlpha() {
    return original == null ? 255 : original.getAlpha();
  }

  @Override
  public void setCornerRadii(float[] radii) {
    if (original != null) original.setCornerRadii(radii);
    invalidateSelf();
  }

  @Override
  public void setColors(int[] colors) {
    if (original != null) original.setColors(colors);
    invalidateSelf();
  }
}
