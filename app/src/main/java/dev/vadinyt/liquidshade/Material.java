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
  boolean useLiveBackdrop;
  private WeakReference<android.widget.ImageView> mediaImage;

  void setMediaImage(android.widget.ImageView v) {
    if ((mediaImage == null ? null : mediaImage.get()) != v) mediaImage = new WeakReference<>(v);
  }

  private final RectF box = new RectF();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
  private final Matrix viewToScreen = new Matrix(),
      rootToScreen = new Matrix(),
      inverseRoot = new Matrix(),
      relative = new Matrix();
  private final float[] artMatrix = new float[9], artUV = new float[4];
  private final float[] nextMap = new float[9], currentMap = new float[9];
  private boolean mapped;
  private final BlurPyramid.Sample blur = new BlurPyramid.Sample();
  private Bitmap bound, coarseBound, fullBound, previewAlbum;

  void setPreviewAlbum(Bitmap bitmap, float left, float top, float scale) {
    previewAlbum = bitmap;
    artUV[0] = artUV[1] = 1 / scale;
    artUV[2] = -left / scale;
    artUV[3] = -top / scale;
  }

  private final Shader blank = new LinearGradient(0, 0, 1, 0, 0, 0, Shader.TileMode.CLAMP);
  private final LensSpring physics = new LensSpring();

  interface Events {
    void drawn(Material material, int backend);

    void motion();

    void rowFrame();
  }

  Events events;
  static final int CANVAS = 1, SURFACE = 2, TEXTURE = 3, ARTWORK = 4, LIVE = 5;
  private int lastBackend;

  private void rendered(int backend) {
    if (tickHasRow && !frameReported && events != null) {
      frameReported = true;
      events.rowFrame();
    }
    if (lastBackend != backend) {
      lastBackend = backend;
      if (events != null) events.drawn(this, backend);
    }
  }

  private float fingerX = -.5f, fingerY = -.65f;
  private boolean contact;

  private BitmapShader sampler;
  private RuntimeShader program;
  private final LiveBackdrop live = new LiveBackdrop();
  private Shader highlightGradient, shadeGradient;
  private int shadeHeight, shadeDim, shadeEnd;
  private int gradientWidth, gradientHeight, gradientColor, gradientAlpha;
  private final Matrix gradientMatrix = new Matrix();
  private int alpha = 255, lastW, lastH;
  private RowMotion rowMotion;
  private boolean tickHasRow, frameReported;
  private final Matrix previewTransform = new Matrix();
  private final RowPose previewPose = new RowPose();

  void bindRow(View row) {
    if (rowMotion == null) rowMotion = new RowMotion(row);
  }

  void previewTransform(Canvas canvas) {
    previewTransform.reset();
    if (!cfg.on(MOTION_ON) || kind != Kind.CARD || response == 0) return;
    View v = owner.get();
    Rect b = getBounds();
    if (v == null || b.isEmpty()) return;
    previewPose.entering = entering;
    previewPose.set(
        b.left,
        b.top,
        b.width(),
        b.height(),
        v.getResources().getDisplayMetrics().density,
        response,
        cfg.unit(MOTION_AMOUNT),
        fingerX,
        fingerY);
    previewTransform.setScale(previewPose.sx, previewPose.sy, previewPose.px, previewPose.py);
    previewTransform.postTranslate(previewPose.dx, previewPose.dy);
    canvas.concat(previewTransform);
  }

  void reveal() {
    if (!contact) {
      fingerX = 0;
      fingerY = -.5f;
      animate(0, 0, true);
    }
  }

  private float topOem = Float.NaN, bottomOem = Float.NaN, response;
  private ValueAnimator spring;
  private boolean pressed, entering;
  private float swipePull;
  private int headerBottom = -1;

  void protectHeader(int bottom) {
    if (headerBottom == bottom) return;
    headerBottom = bottom;
    invalidateSelf();
  }

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

  void rowTouch(View bg, android.view.MotionEvent event) {
    Rect b = getBounds();
    int w = b.isEmpty() ? bg.getWidth() : b.width(), h = b.isEmpty() ? bg.getHeight() : b.height();
    if (w <= 0 || h <= 0) return;
    float x = (event.getX() - bg.getLeft() - b.left) * 2 / w - 1;
    float y = (event.getY() - bg.getTop() - b.top) * 2 / h - 1;
    touch(event.getActionMasked(), Math.max(-1, Math.min(1, x)), Math.max(-1, Math.min(1, y)));
  }

  void touch(int action, float x, float y) {
    if (!cfg.on(PRESS) || !cfg.on(MOTION_ON)) return;
    if (action == 0) {
      contact = true;
      fingerX = x;
      fingerY = y;
      animate(1, 4, false);
    } else if (action == 2 && contact) {
      fingerX = x;
      fingerY = y;
      View v = owner.get();
      if (v != null && spring != null && ValueAnimator.areAnimatorsEnabled()) v.invalidate();
    } else if ((action == 1 || action == 3) && contact) {
      contact = false;
      animate(0, -4, false);
    }
  }

  private void drawMotion(View host) {
    tickHasRow =
        rowMotion != null
            && rowMotion.frame(
                host,
                getBounds(),
                response,
                cfg.unit(MOTION_AMOUNT),
                fingerX,
                fingerY,
                entering,
                swipePull);
    frameReported = false;
    invalidateSelf();
    host.invalidate();
  }

  void swipe(float nativeTranslation) {
    View v = owner.get();
    if (v == null || rowMotion == null || v.getWidth() == 0 || !Float.isFinite(nativeTranslation))
      return;
    float pull = Math.max(-1, Math.min(1, nativeTranslation / (.42f * v.getWidth())));
    if (cfg.get(MOTION_AMOUNT) == 0
        || !cfg.on(MOTION_ON)
        || cfg.get(PERFORMANCE) == 2
        || !ValueAnimator.areAnimatorsEnabled()) pull = 0;
    if (pull != 0 && cfg.on(REDUCE)) {
      android.view.accessibility.AccessibilityManager a =
          v.getContext().getSystemService(android.view.accessibility.AccessibilityManager.class);
      if (a != null && a.isTouchExplorationEnabled()) pull = 0;
    }
    if (Math.abs(pull - swipePull) < .002f && (pull != 0 || swipePull == 0)) return;
    if (swipePull == 0) rowMotion.begin();
    swipePull = pull;
    drawMotion(v);
  }

  void pulse(float impulse) {
    if (!contact) {
      fingerX = -.42f;
      fingerY = -.7f;
    }
    animate(contact ? 1 : 0, impulse * 30, false);
  }

  private void animate(float target, float impulse, boolean reveal) {
    View v = owner.get();
    if (v == null
        || cfg.get(MOTION_AMOUNT) == 0
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
            else physics.sample(t * (entering ? .70f : 1.05f));
            response = Math.max(-.7f, Math.min(1.4f, physics.value));
            View host = owner.get();
            if (host != null) drawMotion(host);
          });
    } else spring.cancel();
    entering = reveal;
    if (rowMotion != null) rowMotion.begin();
    if (reveal) {
      physics.reset();
      physics.value = .98f * Math.min(scale, 1);
    }
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
    if ((next.get(MOTION_AMOUNT) == 0
            || !next.on(MOTION_ON)
            || next.get(PERFORMANCE) == 2
            || (contact && !next.on(PRESS)))
        && (contact || response != 0 || swipePull != 0 || (spring != null && spring.isRunning()))) {
      if (spring != null) spring.cancel();
      if (rowMotion != null) rowMotion.clear();
      tickHasRow = false;
      contact = false;
      response = swipePull = 0;
      physics.reset();
    }
    cfg = next;
  }

  boolean anchoredAt(View v) {
    return anchor.get() == v;
  }

  void suspend() {
    live.release();
    if (rowMotion != null) rowMotion.clear();
    tickHasRow = false;
    if (spring != null) spring.cancel();
    response = swipePull = 0;
    contact = false;
    physics.reset();
    lastBackend = 0;
  }

  void release() {
    suspend();
    if (spring != null) spring.cancel();
    bound = coarseBound = null;
    blur.fine = blur.coarse = null;
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
      if (rowMotion != null) rowMotion.mapToWindow(viewToScreen);
      root.transformMatrixToGlobal(rootToScreen);
      if (!rootToScreen.invert(inverseRoot)) {
        boolean changed = mapped;
        mapped = false;
        return changed;
      }
      relative.setConcat(inverseRoot, viewToScreen);
    } else relative.set(previewTransform);
    relative.getValues(nextMap);
    if (mapped && w == lastW && h == lastH && java.util.Arrays.equals(nextMap, currentMap))
      return false;
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
    if (v.getForeground() != null
        || !(v.getDrawable() instanceof BitmapDrawable d)
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
    if (field && headerBottom >= getBounds().bottom) {
      return;
    }
    float dp = v.getResources().getDisplayMetrics().density;
    box.set(getBounds());
    if (card) box.inset(0, Math.min(box.height() * .12f, dp * 4 * cfg.unit(SPACING)));
    float limit = Math.min(box.width(), box.height()) * .5f;
    if (limit <= 0) return;
    float radius =
        field ? 0 : Math.min(limit, dp * (6 + 44 * cfg.unit(media ? MEDIA_RADIUS : CARD_RADIUS)));
    if (kind == Kind.FOOTER) radius = limit;
    float movement =
        cfg.on(MOTION_ON) && cfg.get(PERFORMANCE) != 2 ? response * cfg.unit(MOTION_AMOUNT) : 0;
    if (!field && movement != 0) {
      // The local optical squeeze adds curvature inside the native row transform.
      float squeeze = Math.max(0f, movement);
      box.inset(dp * 2.3f * squeeze, dp * 2.8f * squeeze);
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
            : .012f + .22f * cfg.unit(TINT) + .25f * cfg.unit(media ? MEDIA_OPACITY : CARD_OPACITY);
    float edge =
        field
            ? 0f
            : cfg.unit(media ? MEDIA_RIM : RIM) * (adaptive && source.luminance < .3f ? .86f : 1f);
    float border = field ? 0f : dp * (.15f + 1.7f * cfg.unit(BORDER));
    float shadeBlur = cfg.on(SHADE_ON) ? cfg.unit(SHADE_BLUR) : 0f;
    // Gaussian radii compose in variance. Keep the material distinct from the open shade
    // even when the user chooses a nearly clear panel.
    float paneSigma = 19f * shadeBlur * (.48f + .52f * shadeBlur);
    float frost = cfg.unit(media ? MEDIA_BLUR : BLUR);
    float lensSigma = field ? 0 : 26f * frost * (.70f + .30f * frost);
    float spread = dp * (float) Math.sqrt(paneSigma * paneSigma + lensSigma * lensSigma);
    int mode = cfg.get(PERFORMANCE), highlight = cfg.get(HIGHLIGHT), sc = cfg.get(SHADE_COLOR);
    float dim = cfg.on(SHADE_ON) ? cfg.unit(DIM) * .60f : 0,
        grade = cfg.on(SHADE_ON) ? cfg.unit(GRADIENT) * .24f : 0;
    float shadeTint = cfg.on(SHADE_ON) ? cfg.unit(SHADE_TINT) * .55f : 0;
    float contrast =
        card && cfg.on(CONTRAST)
            ? (darkText ? 1f - source.luminance : source.luminance) * .32f
            : 0f;
    blur.select(source.levels, spread, frame.getWidth(), frame.getHeight(), mode == 2);
    Bitmap bitmap = allowTexture ? blur.fine : null;
    android.widget.ImageView cover = mediaImage == null ? null : mediaImage.get();
    Bitmap album =
        media && edgeOnly
            ? (cover != null && MediaArtwork.aligned(cover, v)
                ? artworkSource(cover)
                : previewAlbum)
            : null;
    if (media && edgeOnly) bitmap = album;
    boolean hasImage = bitmap != null;
    // This node only sees earlier content in this HWUI target. Other windows stay in Samsung's
    // compositor.
    boolean liveSubmitted = false;
    float bevel = Math.min(limit, dp * (2f + 23 * cfg.unit(BEVEL)) * (.8f + .6f * cfg.unit(DEPTH)));
    float displacement =
        field
            ? 0
            : dp * (3 + 25 * cfg.unit(DEPTH)) * cfg.unit(media ? MEDIA_REFRACTION : REFRACTION);
    if (hasImage || !field || useLiveBackdrop) positionChanged();
    if (!hasImage && useLiveBackdrop && !edgeOnly && mode != 2 && mapped) {
      float savedTop = box.top;
      if (field) box.top = Math.max(box.top, headerBottom);
      // Keep the local pixel mapping exact during clipping, scale and rotation transitions.
      boolean axis =
          Math.abs(currentMap[0] - 1) < .005f
              && Math.abs(currentMap[4] - 1) < .005f
              && Math.abs(currentMap[1]) < .001f
              && Math.abs(currentMap[3]) < .001f;
      boolean inside =
          currentMap[2] + box.left >= 0
              && currentMap[2] + box.right <= frame.getWidth() + .5f
              && currentMap[5] + box.top >= Math.max(0, headerBottom)
              && currentMap[5] + box.bottom <= frame.getHeight() + .5f;
      if (axis && inside && (!field || spread > .25f)) {
        liveSubmitted =
            live.draw(
                canvas,
                box,
                top,
                bottom,
                displacement,
                bevel,
                cfg.unit(COMPRESSION),
                spread * .52f,
                field ? 0 : dp * .8f * cfg.unit(DISPERSION),
                field ? 1f : .3f + 1.4f * cfg.unit(SATURATION),
                field ? 0 : .42f * cfg.unit(VIBRANCY),
                card && cfg.on(CONTRAST) ? (darkText ? -1 : 1) : 0,
                alpha);
      }
      box.top = savedTop;
    }
    // SystemUI often exposes only a transparent layer, not the pixels behind its window.
    // In that case the face cannot physically refract or blur the scene. Map the user optics
    // to restrained frost and edge lighting rather than leaving the controls inert.
    boolean transparentBackend = !hasImage && !liveSubmitted && !field && !edgeOnly;
    float reflection = field ? 0f : cfg.unit(REFLECTION);
    if (transparentBackend) {
      float frost = Math.min(1f, spread / Math.max(1f, dp * 30f));
      amount = Math.min(.68f, amount + .14f * frost);
      edge *= .45f + .34f * cfg.unit(REFRACTION) + .21f * cfg.unit(COMPRESSION);
      reflection *= .75f;
      border *= .84f;
    }
    if (mapped
        && (hasImage || !field || useLiveBackdrop)
        && !shaderFailed
        && canvas.isHardwareAccelerated()
        && frame.getWidth() > 0
        && frame.getHeight() > 0) {
      try {
        if (program == null) {
          program = new RuntimeShader(LensProgram.SOURCE);
          program.setInputShader("src", blank);
          program.setInputShader("clearSrc", blank);
          program.setInputShader("coarseSrc", blank);
        }
        if (bound != bitmap) {
          sampler = bitmap == null ? null : filtered(bitmap);
          program.setInputShader("src", sampler == null ? blank : sampler);
          bound = bitmap;
        }
        Bitmap coarse = album == null && hasImage ? blur.coarse : null;
        if (coarse != coarseBound) {
          program.setInputShader(
              "coarseSrc", coarse == null ? blank : coarse == bitmap ? sampler : filtered(coarse));
          coarseBound = coarse;
        }
        program.setFloatUniform(
            "lowPass",
            coarse == null ? 0f : (float) coarse.getWidth(),
            coarse == null ? 0f : (float) coarse.getHeight(),
            coarse == null ? 0f : blur.mix);
        program.setFloatUniform("face", card ? 1f : 0f, adaptive ? 1f : 0f);
        Bitmap full = album != null ? album : hasImage ? source.image : null;
        if (fullBound != full) {
          if (field) lastBackend = 0;
          program.setInputShader("clearSrc", full == null ? blank : filtered(full));
          fullBound = full;
        }
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
        program.setFloatUniform("body", (float) headerBottom, 22f * dp, field ? 1f : 0f);
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
            reflection);
        program.setFloatUniform(
            "tone",
            field ? 1 : .3f + 1.4f * cfg.unit(SATURATION),
            field ? 0 : .42f * cfg.unit(VIBRANCY),
            amount,
            card && cfg.on(CONTRAST) ? 1f : 0f);
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
        rendered(album != null ? ARTWORK : hasImage ? TEXTURE : liveSubmitted ? LIVE : SURFACE);
        return;
      } catch (RuntimeException | OutOfMemoryError e) {
        shaderFailed = true;
        release();
      }
    }
    rendered(liveSubmitted ? LIVE : CANVAS);
    // Native transparency preserves Samsung's existing compositor blur where available.
    paint.setShader(null);
    paint.setStyle(Paint.Style.FILL);
    if (field) {

      box.top = Math.max(box.top, headerBottom);
    }
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
