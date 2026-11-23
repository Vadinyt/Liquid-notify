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
  private final Path mediaClip = new Path();
  private final Bitmap[] demo;
  private long photoRevision = Long.MIN_VALUE;
  private final android.text.TextPaint linePaint =
      new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
  private final String appLabel, reminder;
  private String fittedLabel = "", fittedReminder = "";
  private int fittedWidth = -1;
  private Config c;
  private float x = .50f, downX, startX;
  private boolean dragged;
  Material.Kind mode = Material.Kind.CARD;

  Preview(Context ctx, Config config) {
    super(ctx);
    c = config;
    appLabel = ctx.getString(R.string.preview_calendar);
    reminder = ctx.getString(R.string.preview_reminder);
    setFocusable(true);
    setClickable(true);
    setContentDescription(ctx.getString(R.string.preview_hint));
    layers = new Material[Material.Kind.values().length];
    for (Material.Kind k : Material.Kind.values())
      layers[k.ordinal()] = new Material(this, this, source, c, k);
    Bitmap b = Bitmap.createBitmap(720, 280, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(b);
    // Curved ridges and a dusk gradient expose both the edge displacement and face blur.
    Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG);
    backdrop.setShader(
        new LinearGradient(
            0,
            0,
            660,
            280,
            new int[] {0xff101d38, 0xff273b61, 0xff65516f, 0xffc88479},
            null,
            Shader.TileMode.CLAMP));
    canvas.drawPaint(backdrop);
    ribbon(canvas, backdrop, 56, 106, 16, 0xff7ccbc8, 0xff285975, 0xff122e50);
    ribbon(canvas, backdrop, 116, 159, 73, 0xffedb8a4, 0xff926b8c, 0xff423959);
    ribbon(canvas, backdrop, 186, 124, 152, 0xffc7b4d8, 0xff716491, 0xff263454);
    source.image = b;
    demo = BlurPyramid.build(b);
    source.levels = demo;
    source.luminance = .46f;
    setClipToOutline(true);
  }

  private static void ribbon(
      Canvas canvas,
      Paint fill,
      float left,
      float bend,
      float right,
      int light,
      int mid,
      int base) {
    Path curve = new Path();
    curve.moveTo(-4, left);
    curve.cubicTo(190, left - 103, 340, bend + 104, 487, bend + 39);
    curve.cubicTo(580, bend + 14, 661, right + 14, 724, right);
    Path shape = new Path(curve);
    shape.lineTo(724, 284);
    shape.lineTo(-4, 284);
    shape.close();
    fill.setStyle(Paint.Style.FILL);
    fill.setShader(
        new LinearGradient(
            310,
            left - 11,
            360,
            left + 92,
            new int[] {light, mid, base},
            new float[] {0, .38f, 1},
            Shader.TileMode.CLAMP));
    canvas.drawPath(shape, fill);
    fill.setShader(null);
    fill.setStyle(Paint.Style.STROKE);
    fill.setColor(0xaaffeee1);
    fill.setStrokeWidth(1.6f);
    canvas.drawPath(curve, fill);
    fill.setColor(Color.WHITE);
  }

  void reloadPhoto() {
    Context ctx = getContext().getApplicationContext();
    long revision = c.on(PHOTO_MODE) ? Bridge.prefs(ctx).getLong("photoRevision", 0) : -1;
    if (photoRevision == revision) return;
    photoRevision = revision;
    if (!c.usesPhoto(BackdropPhoto.exists(ctx))) {
      usePreview(demo);
      return;
    }
    new Thread(
            () -> {
              try {
                Bitmap image =
                    BackdropPhoto.decode(
                        ImageDecoder.createSource(ctx.getContentResolver(), Bridge.PHOTO), 840);
                Bitmap[] levels = BlurPyramid.build(image);
                post(
                    () -> {
                      if (photoRevision == revision) usePreview(levels);
                    });
              } catch (java.io.IOException | RuntimeException | OutOfMemoryError ignored) {
                post(
                    () -> {
                      if (photoRevision == revision) usePreview(demo);
                    });
              }
            },
            "LiquidShade preview")
        .start();
  }

  private void usePreview(Bitmap[] levels) {
    source.image = levels[0];
    source.levels = levels;
    source.luminance = WallpaperSource.measureLight(levels[levels.length - 1]);
    invalidate();
  }

  void config(Config next) {
    c = next;
    reloadPhoto();
    for (Material m : layers) m.configure(c);
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
    int
        cw =
            (int)
                (w
                    * (mode == Material.Kind.FOOTER
                        ? .32f
                        : mode == Material.Kind.CARD ? .83f : .65f)),
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
    glass.allowTexture = c.on(SHADE_ON);
    int rowSave = canvas.save();
    if (active) glass.previewTransform(canvas);
    if (mode == Material.Kind.MEDIA) {
      glass.edgeOnly = true;
      // Draw a sharp album crop first. The same inverse crop feeds the edge-only production lens.
      Bitmap album = demo[0];
      float scale = Math.max(cw / (float) album.getWidth(), ch / (float) album.getHeight());
      float aw = album.getWidth() * scale, ah = album.getHeight() * scale;
      float ax = left + (cw - aw) / 2, ay = top + (ch - ah) / 2;
      float radius =
          Math.min(
              Math.min(cw, ch) / 2f,
              getResources().getDisplayMetrics().density * (6 + 44 * c.unit(MEDIA_RADIUS)));
      area.set(left, top, left + cw, top + ch);
      mediaClip.rewind();
      mediaClip.addRoundRect(area, radius, radius, Path.Direction.CW);
      int save = canvas.save();
      canvas.clipPath(mediaClip);
      area.set(ax, ay, ax + aw, ay + ah);
      p.setColor(Color.WHITE);
      canvas.drawBitmap(album, null, area, p);
      canvas.restoreToCount(save);
      glass.setPreviewAlbum(album, ax, ay, scale);
    }
    if (active) glass.draw(canvas);
    else if (mode != Material.Kind.MEDIA) {
      p.setColor(0xff37414b);
      area.set(left, top, left + cw, top + ch);
      canvas.drawRoundRect(area, ch * .33f, ch * .33f, p);
    }
    float dp = getResources().getDisplayMetrics().density;
    if (mode == Material.Kind.CARD) {
      drawReminder(canvas, left, top, cw, ch, dp);
      canvas.restoreToCount(rowSave);
      return;
    }
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
    canvas.restoreToCount(rowSave);
  }

  private void drawReminder(Canvas canvas, int left, int top, int w, int h, float dp) {
    float cy = top + h * .5f, icon = Math.min(32 * dp, h * .54f), ix = left + 14 * dp;
    float textLeft = ix + icon + 11 * dp, arrow = left + w - 15 * dp;
    p.setColor(0xff557597);
    canvas.drawRoundRect(ix, cy - icon / 2, ix + icon, cy + icon / 2, icon * .31f, icon * .31f, p);
    p.setColor(0xfff1fafb);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(Math.max(1, icon * .052f));
    canvas.drawRoundRect(
        ix + .24f * icon,
        cy - .21f * icon,
        ix + .76f * icon,
        cy + .25f * icon,
        icon * .06f,
        icon * .06f,
        p);
    canvas.drawLine(ix + .25f * icon, cy - .065f * icon, ix + .75f * icon, cy - .065f * icon, p);
    canvas.drawLine(ix + .38f * icon, cy - .28f * icon, ix + .38f * icon, cy - .16f * icon, p);
    canvas.drawLine(ix + .62f * icon, cy - .28f * icon, ix + .62f * icon, cy - .16f * icon, p);
    p.setColor(0xffedf4fe);
    canvas.drawLine(arrow - 3 * dp, cy - dp, arrow, cy + 2 * dp, p);
    canvas.drawLine(arrow, cy + 2 * dp, arrow + 3 * dp, cy - dp, p);
    p.setStyle(Paint.Style.FILL);
    int space = Math.max(0, (int) (arrow - 9 * dp - textLeft));
    float size = Math.min(15 * dp, h * .22f);
    if (fittedWidth != space || linePaint.getTextSize() != size) {
      linePaint.setTypeface(Typeface.DEFAULT);
      linePaint.setTextSize(size * .79f);
      fittedLabel =
          android.text.TextUtils.ellipsize(
                  appLabel, linePaint, space, android.text.TextUtils.TruncateAt.END)
              .toString();
      linePaint.setTextSize(size);
      linePaint.setTypeface(medium);
      fittedReminder =
          android.text.TextUtils.ellipsize(
                  reminder, linePaint, space, android.text.TextUtils.TruncateAt.END)
              .toString();
      fittedWidth = space;
    }
    linePaint.setTypeface(Typeface.DEFAULT);
    linePaint.setTextSize(size * .79f);
    linePaint.setColor(0xffe3eaf3);
    canvas.drawText(fittedLabel, textLeft, cy - 2 * dp, linePaint);
    linePaint.setTypeface(medium);
    linePaint.setTextSize(size);
    linePaint.setColor(Color.WHITE);
    canvas.drawText(fittedReminder, textLeft, cy + size, linePaint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    Material lens = layers[mode.ordinal()];
    Rect bounds = lens.getBounds();
    int action = e.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      if (!bounds.contains((int) e.getX(), (int) e.getY())) return false;
      downX = e.getX();
      startX = x;
      dragged = false;
      getParent().requestDisallowInterceptTouchEvent(true);
    } else if (action == MotionEvent.ACTION_MOVE) {
      float dx = e.getX() - downX;
      if (Math.abs(dx) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) dragged = true;
      if (dragged) x = Math.max(0f, Math.min(1f, startX + dx / Math.max(1, getWidth())));
    }
    lens.touch(
        action,
        Math.max(
            -1f,
            Math.min(1f, (e.getX() - bounds.exactCenterX()) * 2 / Math.max(1, bounds.width()))),
        Math.max(
            -1f,
            Math.min(1f, (e.getY() - bounds.exactCenterY()) * 2 / Math.max(1, bounds.height()))));
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      getParent().requestDisallowInterceptTouchEvent(false);
      if (action == MotionEvent.ACTION_UP && !dragged) performClick();
    }
    invalidate();
    return true;
  }

  void replay() {
    layers[mode.ordinal()].reveal();
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
