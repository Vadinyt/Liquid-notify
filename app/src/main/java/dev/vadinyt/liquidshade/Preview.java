package dev.vadinyt.liquidshade;

import static dev.vadinyt.liquidshade.Option.*;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Draggable lens uses the same material and coordinate transform as SystemUI. */
final class Preview extends View {
  private final WallpaperSource source = new WallpaperSource();
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Material[] layers;
  private final RectF area = new RectF();
  private Config c;
  private float x = .50f;
  Material.Kind mode = Material.Kind.CARD;

  Preview(Context ctx, Config config) {
    super(ctx);
    c = config;
    setFocusable(true);
    setClickable(true);
    setContentDescription(ctx.getString(R.string.preview_hint));
    layers = new Material[Material.Kind.values().length];
    for (Material.Kind k : Material.Kind.values())
      layers[k.ordinal()] = new Material(this, this, source, c, k);
    Bitmap b = Bitmap.createBitmap(720, 280, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(b);
    p.setShader(
        new LinearGradient(
            0,
            0,
            720,
            280,
            new int[] {0xff15334a, 0xff72a4b5, 0xfff6b396, 0xff7f4866},
            null,
            Shader.TileMode.CLAMP));
    canvas.drawPaint(p);
    p.setShader(null);
    Path band = new Path();
    for (int i = 0; i < 5; i++) {
      band.rewind();
      float y = 36 + i * 36;
      band.moveTo(-20, y + 70);
      band.cubicTo(170, y - 100, 420, y + 120, 750, y - 55);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(i == 2 ? 13 : 3);
      p.setColor(i == 2 ? 0xffe5d0b3 : 0x8884c7d1);
      canvas.drawPath(band, p);
    }
    p.setStyle(Paint.Style.FILL);
    source.image = b;
    source.levels = WallpaperSource.pyramid(b);
    source.luminance = .46f;
    setClipToOutline(true);
  }

  void config(Config next) {
    c = next;
    for (Material m : layers) m.cfg = c;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int w = getWidth(), h = getHeight();
    if (w == 0 || h == 0) return;
    Material stage = layers[Material.Kind.SHADE.ordinal()];
    if (c.on(ENABLED) && c.on(SHADE_ON)) {
      stage.setBounds(0, 0, w, h);
      stage.draw(canvas);
    } else {
      float scale =
          Math.max(w / (float) source.image.getWidth(), h / (float) source.image.getHeight());
      float bw = source.image.getWidth() * scale, bh = source.image.getHeight() * scale;
      area.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
      p.setColor(Color.WHITE);
      canvas.drawBitmap(source.image, null, area, p);
    }
    int cw = (int) (w * (mode == Material.Kind.FOOTER ? .32f : .65f)),
        ch =
            (int)
                (h
                    * (mode == Material.Kind.MEDIA
                        ? .72f
                        : mode == Material.Kind.FOOTER ? .42f : .59f));
    int left = Math.max(0, Math.min(w - cw, (int) (w * x) - cw / 2)), top = (h - ch) / 2;
    Material glass = layers[mode.ordinal()];
    glass.setBounds(left, top, left + cw, top + ch);
    glass.artwork = 0xffdfa171;
    boolean active =
        c.on(ENABLED)
            && c.on(
                mode == Material.Kind.MEDIA
                    ? MEDIA_ON
                    : mode == Material.Kind.FOOTER ? FOOTER : CARDS_ON);
    if (active) glass.draw(canvas);
    else {
      p.setColor(0xff37414b);
      area.set(left, top, left + cw, top + ch);
      canvas.drawRoundRect(area, ch * .33f, ch * .33f, p);
    }
    float dp = getResources().getDisplayMetrics().density;
    p.setColor(0xfff3f7fa);
    p.setShadowLayer(2 * dp, 0, dp, 0x80000000);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    p.setTextSize(15 * dp);
    String label =
        getContext()
            .getString(
                mode == Material.Kind.MEDIA
                    ? R.string.preview_media
                    : mode == Material.Kind.FOOTER
                        ? R.string.preview_footer
                        : R.string.preview_cards);
    canvas.drawText(
        label,
        left + (cw - p.measureText(label)) / 2,
        top + ch * .53f - (p.descent() + p.ascent()) / 2,
        p);
    p.clearShadowLayer();
    if (mode == Material.Kind.MEDIA) {
      p.setColor(
          c.get(PROGRESS) == 2
              ? glass.artwork
              : c.get(PROGRESS) == 1 ? c.get(HIGHLIGHT) : 0xffeef5fa);
      canvas.drawRoundRect(
          left + cw * .22f, top + ch * .75f, left + cw * .78f, top + ch * .75f + 2 * dp, dp, dp, p);
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      getParent().requestDisallowInterceptTouchEvent(true);
      if (c.on(PRESS)) layers[mode.ordinal()].pulse(.85f);
    }
    x = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
    invalidate();
    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
      getParent().requestDisallowInterceptTouchEvent(false);
      performClick();
    }
    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL)
      getParent().requestDisallowInterceptTouchEvent(false);
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    for (Material m : layers) m.release();
  }
}
