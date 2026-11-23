package dev.vadinyt.liquidshade;

import android.view.View;
import android.widget.ImageView;
import java.lang.ref.WeakReference;

/**
 * Both artwork slots from Samsung's shade player. Only the stable, visible bitmap can be sampled.
 */
final class MediaArtwork {
  private final WeakReference<ImageView> primary, alternate;
  private final WeakReference<View> reveal;

  MediaArtwork(View primary, View alternate, View reveal) {
    this.primary = new WeakReference<>(primary instanceof ImageView i ? i : null);
    this.alternate = new WeakReference<>(alternate instanceof ImageView i ? i : null);
    this.reveal = new WeakReference<>(reveal);
  }

  static boolean visible(View v, View root) {
    if (v == null || !v.isShown()) return false;
    for (View a = v; a != null; a = a.getParent() instanceof View p ? p : null) {
      if (a.getAlpha() < .995f || a.getVisibility() != View.VISIBLE) return false;
      if (a == root) return true;
    }
    return false;
  }

  ImageView current(View root) {
    ImageView first = primary.get(), second = alternate.get();
    boolean a = visible(first, root) && first.getDrawable() != null;
    boolean b = visible(second, root) && second.getDrawable() != null;
    if ((a && b) || visible(reveal.get(), root)) return null;
    return a ? first : b ? second : null;
  }

  static boolean aligned(View image, View root) {
    if (image.getWidth() != root.getWidth() || image.getHeight() != root.getHeight()) return false;
    for (View p = image; p != root; ) {
      if (!(p.getParent() instanceof View parent)
          || p.getLeft() != 0
          || p.getTop() != 0
          || !p.getMatrix().isIdentity()
          || parent.getScrollX() != 0
          || parent.getScrollY() != 0) return false;
      p = parent;
    }
    return true;
  }
}
