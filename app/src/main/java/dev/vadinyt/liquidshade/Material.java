package dev.vadinyt.liquidshade;

import static dev.vadinyt.liquidshade.Option.*;

import android.animation.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.provider.Settings;
import android.view.View;
import java.lang.ref.WeakReference;

/**
 * A GradientDrawable subtype because One UI updates its drawable layers through that concrete type.
 */
final class Material extends GradientDrawable {
  enum Kind {
    CARD,
    MEDIA,
    FOOTER,
    SHADE
  }

  final Kind kind;
  private final WeakReference<View> owner, anchor;
  final WallpaperSource source;
  Config cfg;
  boolean grouped, darkText, edgeOnly, allowTexture = true, shaderFailed;
  int artwork;
  private final RectF box = new RectF();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
  private final Matrix viewToScreen = new Matrix(),
      rootToScreen = new Matrix(),
      inverseRoot = new Matrix(),
      relative = new Matrix();
  private final float[] artMatrix = new float[9], artUV = new float[4];
  private final float[] nextMap = new float[9], currentMap = new float[9];
  private boolean mapped;
  private Bitmap bound, fullBound;
  private final Shader blank = new LinearGradient(0, 0, 1, 0, 0, 0, Shader.TileMode.CLAMP);
  private final LensSpring physics = new LensSpring();

  interface Events {
    void drawn(Material material, int backend);

    void motion();
  }

  Events events;
  static final int CANVAS = 1, SURFACE = 2, TEXTURE = 3, ARTWORK = 4;
  private int lastBackend;

  private void rendered(int backend) {
    if (lastBackend != backend) {
      lastBackend = backend;
      if (events != null) events.drawn(this, backend);
    }
  }

  private float fingerX = -.5f, fingerY = -.65f;
  private boolean contact;

  private BitmapShader sampler;
  private RuntimeShader program;
  private final NativeBlur nativeBlur = new NativeBlur();
  private Shader highlightGradient, shadeGradient;
  private int shadeHeight, shadeDim, shadeEnd;
  private int gradientWidth, gradientHeight, gradientColor, gradientAlpha;
  private final Matrix gradientMatrix = new Matrix();
  private int alpha = 255, lastW, lastH;
  private long lastMove;
  private float topOem = Float.NaN, bottomOem = Float.NaN, response;
  private ValueAnimator spring;
  private boolean pressed;
  private final Path clip = new Path();
  private final float[] corners = new float[8];

  Material(View view, View frame, WallpaperSource source, Config cfg, Kind kind) {
    this.owner = new WeakReference<>(view);
    this.anchor = new WeakReference<>(frame);
    this.source = source;
    this.cfg = cfg;
    this.kind = kind;
  }

  @Override
  public void setCornerRadii(float[] r) {
    super.setCornerRadii(r);
    if (r != null && r.length >= 8) {
      topOem = r[0];
      bottomOem = r[4];
    }
  }

  @Override
  public void setAlpha(int n) {
    super.setAlpha(n);
    alpha = n;
  }

  @Override
  public int getAlpha() {
    return alpha;
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override
  public boolean isStateful() {
    return true;
  }

  @Override
  protected boolean onStateChange(int[] states) {
    boolean down = false;
    for (int state : states) if (state == android.R.attr.state_pressed) down = true;
    // The notification row owns touch dispatch. Drawable state also supports footer buttons.
    if (kind == Kind.FOOTER && down != pressed && cfg != null) touch(down ? 0 : 1, 0, -.4f);
    pressed = down;
    return super.onStateChange(states);
  }

  void touch(int action, float x, float y) {
    if (!cfg.on(PRESS) || !cfg.on(MOTION_ON)) return;
    if (action == 0) {
      contact = true;
      fingerX = x;
      fingerY = y;
      animate(1, 4);
    } else if (action == 2 && contact) {
      fingerX = x;
      fingerY = y;
      View v = owner.get();
      if (v != null && spring != null && ValueAnimator.areAnimatorsEnabled()) v.invalidate();
    } else if ((action == 1 || action == 3) && contact) {
      contact = false;
      animate(0, -4);
    }
  }

  void pulse(float impulse) {
    if (!contact) {
      fingerX = -.42f;
      fingerY = -.7f;
    }
    animate(contact ? 1 : 0, impulse * 25);
  }

  private void animate(float target, float impulse) {
    View v = owner.get();
    if (v == null
        || !cfg.on(MOTION_ON)
        || cfg.get(PERFORMANCE) == 2
        || !ValueAnimator.areAnimatorsEnabled()) return;
    float scale =
        cfg.on(REDUCE)
            ? Settings.Global.getFloat(
                v.getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1)
            : 1;
    android.view.accessibility.AccessibilityManager access =
        v.getContext().getSystemService(android.view.accessibility.AccessibilityManager.class);
    if (scale <= 0 || (cfg.on(REDUCE) && access != null && access.isTouchExplorationEnabled()))
      return;
    if (spring == null) {
      spring = ValueAnimator.ofFloat(0, 1);
      spring.setInterpolator(new android.view.animation.LinearInterpolator());
      spring.addUpdateListener(
          a -> {
            float t = a.getAnimatedFraction();
            if (t >= .9999f) physics.finish();
            else physics.sample(t * 1.05f);
            response = Math.max(-.7f, Math.min(1.4f, physics.value));
            View host = owner.get();
            if (host != null) host.invalidate();
          });
    } else spring.cancel();
    float goal = target * Math.min(scale, 1f);
    physics.target(goal, impulse * Math.min(1, scale), cfg.unit(STIFFNESS), cfg.unit(DAMPING));
    spring.setDuration((long) (1050 / (.65f + 1.5f * cfg.unit(SPEED))));
    spring.start();
    if (events != null) events.motion();
  }

  boolean hasDrawnBackdrop() {
    return lastBackend == TEXTURE && fullBound != null && fullBound == source.image;
  }

  void configure(Config next) {
    if ((!next.on(MOTION_ON) || next.get(PERFORMANCE) == 2 || (contact && !next.on(PRESS)))
        && (contact || response != 0 || (spring != null && spring.isRunning()))) {
      if (spring != null) spring.cancel();
      contact = false;
      response = 0;
      physics.reset();
    }
    cfg = next;
  }

  boolean anchoredAt(View v) {
    return anchor.get() == v;
  }

  void suspend() {
    nativeBlur.suspend();
    if (spring != null) spring.cancel();
    response = 0;
    contact = false;
    physics.reset();
    lastBackend = 0;
  }

  void release() {
    suspend();
    if (spring != null) spring.cancel();
    bound = null;
    fullBound = null;
    sampler = null;
    program = null;
    paint.setShader(null);
  }

  boolean positionChanged() {
    View v = owner.get(), root = anchor.get();
    if (v == null || root == null) return false;
    int w = root.getWidth(), h = root.getHeight();
    relative.reset();
    if (v != root) {
      viewToScreen.reset();
      rootToScreen.reset();
      v.transformMatrixToGlobal(viewToScreen);
      root.transformMatrixToGlobal(rootToScreen);
      if (!rootToScreen.invert(inverseRoot)) {
        boolean changed = mapped;
        mapped = false;
        return changed;
      }
      relative.setConcat(inverseRoot, viewToScreen);
    }
    relative.getValues(nextMap);
    if (mapped && w == lastW && h == lastH && java.util.Arrays.equals(nextMap, currentMap))
      return false;
    if (mapped
        && kind == Kind.CARD
        && Math.abs(nextMap[2] - currentMap[2]) > 4
        && android.os.SystemClock.uptimeMillis() - lastMove > 450) {
      lastMove = android.os.SystemClock.uptimeMillis();
      pulse(.28f);
    }
    System.arraycopy(nextMap, 0, currentMap, 0, 9);
    lastW = w;
    lastH = h;
    mapped = true;
    return true;
  }

  /**
   * ImageView draws its bitmap inside drawable bounds, then applies the image matrix and padding.
   * Only accept an opaque full-bleed mapping whose exact pixels we can reconstruct.
   */
  private Bitmap artworkSource(android.widget.ImageView v) {
    if (!(v.getDrawable() instanceof BitmapDrawable d)
        || d.getGravity() != android.view.Gravity.FILL
        || d.getTileModeX() != null
        || d.getTileModeY() != null
        || d.getAlpha() != 255
        || d.getColorFilter() != null
        || v.getImageTintList() != null
        || v.getColorFilter() != null
        || d.isAutoMirrored()
        || d.getPaint().getShader() != null) return null;
    Bitmap b = d.getBitmap();
    Rect bounds = d.getBounds();
    if (b == null || b.isRecycled() || bounds.isEmpty()) return null;
    v.getImageMatrix().getValues(artMatrix);
    if (artMatrix[0] <= 0
        || artMatrix[4] <= 0
        || artMatrix[1] != 0
        || artMatrix[3] != 0
        || artMatrix[6] != 0
        || artMatrix[7] != 0
        || artMatrix[8] != 1) return null;
    float left = v.getPaddingLeft() + artMatrix[2] + artMatrix[0] * bounds.left;
    float top = v.getPaddingTop() + artMatrix[5] + artMatrix[4] * bounds.top;
    float w = bounds.width() * artMatrix[0], h = bounds.height() * artMatrix[4];
    if (left > .5f || top > .5f || left + w < v.getWidth() - .5f || top + h < v.getHeight() - .5f)
      return null;
    artUV[0] = b.getWidth() / w;
    artUV[1] = b.getHeight() / h;
    artUV[2] = -left * artUV[0];
    artUV[3] = -top * artUV[1];
    return b;
  }

  private static BitmapShader filtered(Bitmap bitmap) {
    BitmapShader sample = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    sample.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
    return sample;
  }

  private static float ch(int color, int shift) {
    return ((color >> shift) & 255) / 255f;
  }

  private static int opacity(int color, float a) {
    return (color & 0xffffff) | (Math.round(255 * Math.max(0f, Math.min(1f, a))) << 24);
  }

  @Override
  public void draw(Canvas canvas) {
    View v = owner.get(), frame = anchor.get();
    if (v == null || frame == null || cfg == null || getBounds().isEmpty() || alpha == 0) return;
    boolean media = kind == Kind.MEDIA, field = kind == Kind.SHADE, card = kind == Kind.CARD;
    float dp = v.getResources().getDisplayMetrics().density;
    box.set(getBounds());
    if (card) box.inset(0, Math.min(box.height() * .12f, dp * 4 * cfg.unit(SPACING)));
    float limit = Math.min(box.width(), box.height()) * .5f;
    if (limit <= 0) return;
    float radius =
        field ? 0 : Math.min(limit, dp * (6 + 44 * cfg.unit(media ? MEDIA_RADIUS : CARD_RADIUS)));
    if (kind == Kind.FOOTER) radius = limit;
    float movement = cfg.on(MOTION_ON) && cfg.get(PERFORMANCE) != 2 ? response : 0;
    if (!field && movement != 0) {
      // Optical squeeze happens inside the native hit target. Artwork and text keep their geometry.
      float squeeze = Math.max(0f, movement);
      box.inset(dp * 1.5f * squeeze, dp * 1.9f * squeeze);
      radius = Math.max(0, Math.min(limit, radius + dp * 3 * movement));
    }
    float top = radius, bottom = radius;
    if (card && grouped && cfg.get(GROUPS) == 1) {
      top = Math.min(radius, dp * 11);
      bottom = top;
    }
    if (card && grouped && cfg.get(GROUPS) != 2) {
      if (!Float.isNaN(topOem)) top = Math.min(top, topOem);
      if (!Float.isNaN(bottomOem)) bottom = Math.min(bottom, bottomOem);
    }
    boolean adaptive = cfg.on(AUTO_MODE);
    boolean night =
        (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    int tint = cfg.get(BASE_COLOR);
    if (adaptive && night && source.luminance < .40) {
      tint =
          Color.rgb(
              (Color.red(tint) * 3 + 38) / 4,
              (Color.green(tint) * 3 + 50) / 4,
              (Color.blue(tint) * 3 + 66) / 4);
    }
    if (media && cfg.on(ART_TINT) && artwork != 0) tint = artwork;
    float amount =
        field
            ? 0f
            : .02f + .25f * cfg.unit(TINT) + .30f * cfg.unit(media ? MEDIA_OPACITY : CARD_OPACITY);
    float edge =
        field
            ? 0f
            : cfg.unit(media ? MEDIA_RIM : RIM) * (adaptive && source.luminance < .3f ? .86f : 1f);
    float border = field ? 0f : dp * (.15f + 1.7f * cfg.unit(BORDER));
    float shadeBlur = cfg.on(SHADE_ON) ? cfg.unit(SHADE_BLUR) : 0f;
    float spread =
        dp
            * (16 * shadeBlur * shadeBlur
                + (field
                    ? 0
                    : 22
                        * cfg.unit(media ? MEDIA_BLUR : BLUR)
                        * cfg.unit(media ? MEDIA_BLUR : BLUR)))
            * (adaptive && source.luminance > .65f ? 1.1f : 1);
    int mode = cfg.get(PERFORMANCE), highlight = cfg.get(HIGHLIGHT), sc = cfg.get(SHADE_COLOR);
    float dim = cfg.on(SHADE_ON) ? cfg.unit(DIM) * .60f : 0,
        grade = cfg.on(SHADE_ON) ? cfg.unit(GRADIENT) * .24f : 0;
    float shadeTint = cfg.on(SHADE_ON) ? cfg.unit(SHADE_TINT) * .55f : 0;
    float contrast =
        card && cfg.on(CONTRAST)
            ? (darkText ? 1f - source.luminance : source.luminance) * .32f
            : 0f;
    Bitmap bitmap =
        allowTexture ? source.forBlur(spread, frame.getWidth(), frame.getHeight()) : null;
    Bitmap album =
        media && edgeOnly && v instanceof android.widget.ImageView cover
            ? artworkSource(cover)
            : null;
    if (album != null) bitmap = album;
    boolean hasImage = bitmap != null;
    if (hasImage || !field) positionChanged();
    if (mapped
        && (hasImage || !field)
        && !shaderFailed
        && canvas.isHardwareAccelerated()
        && frame.getWidth() > 0
        && frame.getHeight() > 0) {
      try {
        nativeBlur.suspend();
        if (program == null) {
          program = new RuntimeShader(LensProgram.SOURCE);
          program.setInputShader("src", blank);
          program.setInputShader("clearSrc", blank);
        }
        if (bound != bitmap) {
          sampler = bitmap == null ? null : filtered(bitmap);
          program.setInputShader("src", sampler == null ? blank : sampler);
          bound = bitmap;
        }
        Bitmap full = album != null ? album : hasImage ? source.image : null;
        if (fullBound != full) {
          if (field) lastBackend = 0;
          program.setInputShader("clearSrc", full == null ? blank : filtered(full));
          fullBound = full;
        }
        float bevel =
            Math.min(limit, dp * (1.5f + 19 * cfg.unit(BEVEL)) * (.8f + .6f * cfg.unit(DEPTH)));
        float displacement =
            field
                ? 0
                : dp * (2 + 20 * cfg.unit(DEPTH)) * cfg.unit(media ? MEDIA_REFRACTION : REFRACTION);
        program.setFloatUniform("center", box.centerX(), box.centerY());
        program.setFloatUniform("extent", box.width() / 2, box.height() / 2);
        program.setFloatUniform("mapX", currentMap[0], currentMap[1], currentMap[2]);
        program.setFloatUniform("mapY", currentMap[3], currentMap[4], currentMap[5]);
        program.setFloatUniform("mapW", currentMap[6], currentMap[7], currentMap[8]);
        program.setFloatUniform("frame", (float) frame.getWidth(), (float) frame.getHeight());
        program.setFloatUniform(
            "texture",
            hasImage ? (float) bitmap.getWidth() : 1f,
            hasImage ? (float) bitmap.getHeight() : 1f);
        program.setFloatUniform(
            "fullSize",
            hasImage ? (float) full.getWidth() : 1f,
            hasImage ? (float) full.getHeight() : 1f);
        program.setFloatUniform(
            "surface", fingerX, fingerY, album != null ? 2 : hasImage ? 1 : 0, dp);
        program.setFloatUniform("artUV", artUV[0], artUV[1], artUV[2], artUV[3]);
        program.setFloatUniform("radii", top, bottom);
        program.setFloatUniform(
            "optic",
            displacement,
            bevel,
            field ? 0 : cfg.unit(COMPRESSION),
            mode == 2 ? 0 : spread * .42f);
        program.setFloatUniform(
            "finish",
            field || mode == 2 ? 0f : dp * (mode == 0 ? .85f : .55f) * cfg.unit(DISPERSION),
            border,
            edge,
            field ? 0 : cfg.unit(REFLECTION));
        program.setFloatUniform(
            "tone",
            field ? 1 : .3f + 1.4f * cfg.unit(SATURATION),
            field ? 0 : .42f * cfg.unit(VIBRANCY),
            amount,
            0);
        program.setFloatUniform("accent", ch(tint, 16), ch(tint, 8), ch(tint, 0), 1);
        program.setFloatUniform("shade", ch(sc, 16), ch(sc, 8), ch(sc, 0), shadeTint);
        program.setFloatUniform("wash", dim, grade, darkText ? 1f : 0f, 0);
        program.setFloatUniform(
            "flags",
            ch(highlight, 16),
            ch(highlight, 8),
            ch(highlight, 0),
            field || mode == 2 ? 0 : mode == 0 ? .94f : .76f);
        program.setFloatUniform(
            "detail", alpha / 255f, edgeOnly ? Math.max(2f, bevel) : 0f, movement, contrast);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShader(program);
        canvas.drawRect(box, paint);
        rendered(album != null ? ARTWORK : hasImage ? TEXTURE : SURFACE);
        return;
      } catch (RuntimeException | OutOfMemoryError e) {
        shaderFailed = true;
        release();
      }
    }
    rendered(CANVAS);
    // Native transparency preserves Samsung's existing compositor blur where available.
    paint.setShader(null);
    paint.setStyle(Paint.Style.FILL);
    if (field) nativeBlur.draw(v, canvas, getBounds(), Math.round(spread));
    corners[0] = corners[1] = corners[2] = corners[3] = top;
    corners[4] = corners[5] = corners[6] = corners[7] = bottom;
    clip.rewind();
    clip.addRoundRect(box, corners, Path.Direction.CW);
    if (!edgeOnly) {
      paint.setColor(opacity(field ? sc : tint, (field ? shadeTint : amount) * alpha / 255f));
      canvas.drawPath(clip, paint);
      if (field) {
        int start = opacity(Color.BLACK, dim + grade),
            end = opacity(Color.BLACK, dim),
            h = Math.max(1, (int) box.height());
        if (shadeGradient == null || shadeHeight != h || shadeDim != start || shadeEnd != end) {
          shadeGradient =
              new LinearGradient(0, box.top, 0, box.top + h, start, end, Shader.TileMode.CLAMP);
          shadeHeight = h;
          shadeDim = start;
          shadeEnd = end;
        }
        paint.setShader(shadeGradient);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        canvas.drawPath(clip, paint);
        paint.setShader(null);
      } else {
        paint.setColor(opacity(darkText ? Color.WHITE : Color.BLACK, contrast * alpha / 255f));
        canvas.drawPath(clip, paint);
      }
    }
    if (!field) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(border);
      int w = Math.max(1, (int) box.width()), h = Math.max(1, (int) box.height());
      int a = Math.round(255 * (edge * .68f + cfg.unit(REFLECTION) * .12f));
      if (highlightGradient == null
          || gradientWidth != w
          || gradientHeight != h
          || gradientColor != highlight
          || gradientAlpha != a) {
        int peak = opacity(highlight, a / 255f), dimEdge = opacity(highlight, a / 1275f);
        highlightGradient =
            new LinearGradient(
                0,
                0,
                w,
                h,
                new int[] {peak, dimEdge, opacity(highlight, a / 420f)},
                null,
                Shader.TileMode.CLAMP);
        gradientWidth = w;
        gradientHeight = h;
        gradientColor = highlight;
        gradientAlpha = a;
      }
      gradientMatrix.setTranslate(box.left, box.top);
      highlightGradient.setLocalMatrix(gradientMatrix);
      paint.setShader(highlightGradient);
      paint.setColor(Color.WHITE);
      paint.setAlpha(alpha);
      canvas.drawPath(clip, paint);
      paint.setShader(null);
    }
  }
}
