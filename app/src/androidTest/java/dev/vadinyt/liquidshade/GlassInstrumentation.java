package dev.vadinyt.liquidshade;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Installed only on the CI emulator. PixelCopy reads this test activity's own window. */
public final class GlassInstrumentation extends Instrumentation {
  private boolean requireLive;
  private Activity activity;
  private FrameLayout row, stage;
  private View background;
  private Material glass;
  private final WallpaperSource source = new WallpaperSource();
  private Config config;
  private int backend, rowFrames;
  private final AtomicInteger clicks = new AtomicInteger();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    requireLive = args != null && "true".equals(args.getString("requireLive"));
    start();
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    int code = Activity.RESULT_CANCELED;
    try {

      Intent launch =
          new Intent(getTargetContext(), SettingsActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = startActivitySync(launch);
      String live = testChangingScene();
      testMediaSlots();
      runOnMainSync(this::install);
      waitForIdleSync();
      SystemClock.sleep(550);
      Rect before = marker(copy());
      check(before.width() > 15, "marker absent at rest: " + before);
      int[] at = new int[2];
      runOnMainSync(() -> row.getLocationInWindow(at));
      float x = at[0] + 239, y = at[1] + 48;
      long down = SystemClock.uptimeMillis();
      pointer(MotionEvent.ACTION_DOWN, down, x, y);
      SystemClock.sleep(480);
      Rect held = marker(copy());
      check(held.left - before.left >= 5, "foreground did not move: " + before + " -> " + held);
      check(
          backend == Material.SURFACE && rowFrames > 0,
          "lit fallback or row redraw missing: " + backend + " / " + rowFrames);
      pointer(MotionEvent.ACTION_UP, down, x, y);
      SystemClock.sleep(1500);
      Rect after = marker(copy());
      check(
          Math.abs(after.left - before.left) <= 1 && clicks.get() == 1,
          "release/click failed: " + after + " clicks=" + clicks.get());
      runOnMainSync(
          () -> {
            check(
                row.getAnimationMatrix() == null && row.getTranslationY() == 57f,
                "native translation changed");
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.CYAN);
            source.image = bitmap;
            source.levels = BlurPyramid.build(bitmap);
            glass.allowTexture = true;
            background.invalidate();
          });
      SystemClock.sleep(180);
      Bitmap im = copy();
      check(backend == Material.TEXTURE, "image shader not drawn");
      int[] loc = new int[2];
      runOnMainSync(() -> background.getLocationInWindow(loc));
      int center = im.getPixel(loc[0] + 150, loc[1] + 55);
      check(
          Color.blue(center) > Color.red(center) + 75
              && Color.green(center) > Color.red(center) + 75,
          "backdrop sample missing " + Integer.toHexString(center));
      int openingPixels = testReveal(after);
      testFeedback();
      runOnMainSync(
          () -> {
            row.setTranslationX(19);
            glass.swipe(125);
            check(
                row.getAnimationMatrix() != null && row.getTranslationX() == 19,
                "swipe motion missing");
            glass.swipe(0);
            check(
                row.getAnimationMatrix() == null && row.getTranslationX() == 19,
                "swipe did not release");
            row.setTranslationX(0);
            Matrix foreign = new Matrix();
            foreign.setTranslate(3, 2);
            row.setAnimationMatrix(foreign);
            glass.reveal();
          });
      SystemClock.sleep(200);
      runOnMainSync(
          () -> {
            glass.suspend();
            Matrix expected = new Matrix();
            expected.setTranslate(3, 2);
            check(expected.equals(row.getAnimationMatrix()), "foreign matrix replaced");
            row.setAnimationMatrix(null);
          });
      int headerPixels = testNativeHeader();
      String finish = testOpticalFinish();
      report.putString(
          "stream",
          "LIQUIDSHADE_DEVICE_PASS marker="
              + before.left
              + "->"
              + held.left
              + "->"
              + after.left
              + " rowDraws="
              + rowFrames
              + " click="
              + clicks.get()
              + " openingTravelPx="
              + openingPixels
              + " protectedHeaderPixels="
              + headerPixels
              + " stripeRange="
              + finish
              + " liveScene="
              + live
              + " mediaSlots=switched feedback=restored swipe=restored");
      code = Activity.RESULT_OK;
    } catch (Throwable e) {
      android.util.Log.e("LiquidShadeTest", "Instrumentation failed", e);
      report.putString("stream", "FAIL " + e);
    } finally {
      if (activity != null) runOnMainSync(() -> activity.finish());
      finish(code, report);
    }
  }

  private int testReveal(Rect rest) throws Exception {
    runOnMainSync(
        () -> {
          config.set(Option.SPEED, 15);
          config.set(Option.STIFFNESS, 40);
          glass.reveal();
        });
    int travel = 0;
    long until = SystemClock.uptimeMillis() + 600;
    do {
      travel = Math.max(travel, marker(copy()).top - rest.top);
    } while (travel < 10 && SystemClock.uptimeMillis() < until);
    check(travel >= 10, "opening travel missing: " + travel);
    SystemClock.sleep(1550);
    check(Math.abs(marker(copy()).top - rest.top) <= 1, "opening failed to return to rest");
    return travel;
  }

  private void testFeedback() {
    runOnMainSync(
        () -> {
          android.graphics.drawable.GradientDrawable original =
              new android.graphics.drawable.GradientDrawable();
          original.setColor(Color.RED);
          original.setBounds(0, 0, 20, 20);
          RecoilFeedback layer = new RecoilFeedback(original);
          Bitmap pixels = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
          Canvas canvas = new Canvas(pixels);
          layer.draw(canvas);
          check(pixels.getPixel(10, 10) == 0, "Samsung idle fill obscures the lens");
          layer.setState(new int[] {android.R.attr.state_focused});
          layer.draw(canvas);
          check(pixels.getPixel(10, 10) == Color.RED, "native focus missing");
          layer.setColor(Color.BLUE);
          layer.setState(new int[0]);
          pixels.eraseColor(0);
          layer.draw(canvas);
          check(pixels.getPixel(10, 10) == 0, "idle fill returned after rebind");
          original.draw(canvas);
          check(pixels.getPixel(10, 10) == Color.BLUE, "native colour update lost on restoration");
        });
  }

  // Recreate the failing z-order: a genuine Android clock and status marker in a lower
  // sibling, followed by a full-height transparent panel whose drawable can obscure them.
  private int testNativeHeader() throws Exception {
    View[] plane = new View[1];
    Material[] backdrop = new Material[1];
    int boundary = 176;
    runOnMainSync(
        () -> {
          FrameLayout scene = new FrameLayout(activity);
          scene.setBackgroundColor(0xff182532);
          TextClock clock = new TextClock(activity);
          clock.setFormat12Hour("'17:38'");
          clock.setFormat24Hour("'17:38'");
          clock.setTextColor(Color.WHITE);
          clock.setTextSize(22);
          FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(230, 84);
          lp.leftMargin = 16;
          lp.topMargin = 50;
          scene.addView(clock, lp);
          View status = new View(activity);
          status.setBackgroundColor(Color.MAGENTA);
          FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(24, 18);
          sp.leftMargin = 255;
          sp.topMargin = 36;
          scene.addView(status, sp);
          plane[0] = new View(activity);
          scene.addView(plane[0], new FrameLayout.LayoutParams(-1, -1));
          backdrop[0] = new Material(plane[0], plane[0], source, config, Material.Kind.SHADE);
          backdrop[0].protectHeader(boundary);
          activity.setContentView(scene);
        });
    SystemClock.sleep(180);
    int[] where = new int[2];
    runOnMainSync(() -> plane[0].getLocationInWindow(where));
    Bitmap original = copy();
    runOnMainSync(() -> plane[0].setBackground(backdrop[0]));
    SystemClock.sleep(180);
    Bitmap glass = copy();
    int tested = 0, bright = 0;
    for (int y = 2; y < boundary; y++)
      for (int x = 3; x < 296; x++) {
        int a = original.getPixel(where[0] + x, where[1] + y);
        check(a == glass.getPixel(where[0] + x, where[1] + y), "header overdraw at " + x + "," + y);
        if (Color.red(a) > 200 && Color.green(a) > 200 && Color.blue(a) > 200) bright++;
        tested++;
      }
    check(bright > 15, "clock glyphs missing in fixture");
    int body = glass.getPixel(where[0] + 170, where[1] + boundary + 175);
    check(Color.green(body) > Color.red(body) + 70, "wallpaper was not rendered below clock");
    return tested;
  }

  /**
   * Known source, real view, GPU window pixels. Only the user blur slider changes between samples.
   */
  private String testOpticalFinish() throws Exception {
    Config look = new Config();
    look.set(Option.PERFORMANCE, 0);
    for (Option o :
        new Option[] {
          Option.BLUR,
          Option.SHADE_BLUR,
          Option.DIM,
          Option.SHADE_TINT,
          Option.GRADIENT,
          Option.CONTRAST,
          Option.TINT,
          Option.CARD_OPACITY,
          Option.REFLECTION,
          Option.RIM
        }) look.set(o, 0);
    Bitmap stripes = Bitmap.createBitmap(360, 160, Bitmap.Config.ARGB_8888);
    Canvas pattern = new Canvas(stripes);
    pattern.drawColor(0xff222222);
    Paint ink = new Paint();
    ink.setColor(0xffdddddd);
    for (int x = 0; x < 360; x += 12) pattern.drawRect(x, 0, x + 6, 160, ink);
    WallpaperSource src = new WallpaperSource();
    src.image = stripes;
    src.levels = BlurPyramid.build(stripes);
    Material[] lens = new Material[1];
    FrameLayout[] card = new FrameLayout[1];
    runOnMainSync(
        () -> {
          FrameLayout root = new FrameLayout(activity);
          card[0] = new FrameLayout(activity);
          root.addView(card[0], new FrameLayout.LayoutParams(360, 160));
          lens[0] = new Material(card[0], card[0], src, look, Material.Kind.CARD);
          card[0].setBackground(lens[0]);
          // Foreground icon is outside the shader's input.
          View icon = new View(activity);
          icon.setBackgroundColor(Color.MAGENTA);
          FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(14, 14);
          lp.topMargin = 50;
          lp.leftMargin = 78;
          card[0].addView(icon, lp);
          activity.setContentView(root);
        });
    SystemClock.sleep(180);
    int[] at = new int[2];
    runOnMainSync(() -> card[0].getLocationInWindow(at));
    Bitmap before = copy();
    runOnMainSync(
        () -> {
          look.set(Option.BLUR, 44);
          lens[0].configure(look);
          card[0].invalidate();
        });
    SystemClock.sleep(180);
    Bitmap after = copy();
    int sharp = lumaRange(before, at), soft = lumaRange(after, at);
    int ix = at[0] + 84, iy = at[1] + 56;
    check(
        before.getPixel(ix, iy) == Color.MAGENTA && after.getPixel(ix, iy) == Color.MAGENTA,
        "blur touched foreground");
    check(sharp > 145 && soft < 25, "centre blur failed " + sharp + " -> " + soft);
    return sharp + "->" + soft;
  }

  private static int lumaRange(Bitmap b, int[] at) {
    int low = 255, high = 0;
    for (int x = 110; x < 245; x++) {
      int v = Color.green(b.getPixel(at[0] + x, at[1] + 84));
      low = Math.min(low, v);
      high = Math.max(high, v);
    }
    return high - low;
  }

  // Two different scenes pass below one installed production lens, without a backing bitmap.
  private String testChangingScene() throws Exception {
    final int width = 460, height = 178;
    final int[] colors = {0xff18545c, 0xff923e54};
    Material[] material = new Material[1];
    FrameLayout[] panel = new FrameLayout[1];
    View[] scene = new View[1];
    Config look = new Config();
    look.set(Option.PERFORMANCE, 0);
    look.set(Option.CONTRAST, 0);
    look.set(Option.CARD_RADIUS, 35);
    look.set(Option.SPACING, 0);
    look.set(Option.REFRACTION, 100);
    look.set(Option.BEVEL, 72);
    look.set(Option.BLUR, 0);
    look.set(Option.SHADE_BLUR, 0);
    AtomicInteger color = new AtomicInteger(colors[0]);
    runOnMainSync(
        () -> {
          FrameLayout root = new FrameLayout(activity);
          scene[0] =
              new View(activity) {
                final Paint ink = new Paint();

                @Override
                protected void onDraw(Canvas canvas) {
                  canvas.drawColor(color.get());
                  ink.setColor(0xffedc785);
                  for (int x = 0; x < getWidth() + 100; x += 47)
                    canvas.drawRect(x, 0, x + 6, getHeight(), ink);
                }
              };
          root.addView(scene[0], new FrameLayout.LayoutParams(-1, -1));
          panel[0] = new FrameLayout(activity);
          FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(width, height);
          pos.leftMargin = 32;
          pos.topMargin = 78;
          root.addView(panel[0], pos);
          Material m =
              new Material(panel[0], root, new WallpaperSource(), look, Material.Kind.CARD);
          material[0] = m;
          m.useLiveBackdrop = true;
          m.events =
              new Material.Events() {
                public void drawn(Material glass, int b) {
                  backend = b;
                }

                public void motion() {}

                public void rowFrame() {}
              };
          panel[0].setBackground(m);
          TextView title = new TextView(activity);
          title.setText("Agenda");
          title.setTextColor(Color.WHITE);
          title.setTextSize(18);
          title.setGravity(Gravity.CENTER_VERTICAL);
          FrameLayout.LayoutParams text = new FrameLayout.LayoutParams(215, 70);
          text.leftMargin = 84;
          text.topMargin = 45;
          panel[0].addView(title, text);
          View icon = new View(activity);
          icon.setBackgroundColor(0xff56daae);
          FrameLayout.LayoutParams ico = new FrameLayout.LayoutParams(34, 34);
          ico.leftMargin = 35;
          ico.topMargin = 62;
          panel[0].addView(icon, ico);
          activity.setContentView(root);
        });
    SystemClock.sleep(220);
    int[] at = new int[2];
    runOnMainSync(() -> panel[0].getLocationInWindow(at));
    Bitmap green = copy();
    runOnMainSync(
        () -> {
          color.set(colors[1]);
          scene[0].invalidate();
        });
    SystemClock.sleep(180);
    Bitmap rose = copy();
    int cx = at[0] + 309, cy = at[1] + 83;
    check(
        Color.red(rose.getPixel(cx, cy)) - Color.red(green.getPixel(cx, cy)) > 62,
        "same card failed to transmit scene change");
    int ix = at[0] + 45, iy = at[1] + 76;
    check(
        green.getPixel(ix, iy) == 0xff56daae && rose.getPixel(ix, iy) == 0xff56daae,
        "foreground was filtered");
    int previous = backend;
    // Disable both specular lobes for the displacement measurement. Only the background
    // sample coordinates can then move the high-contrast stripes.
    runOnMainSync(
        () -> {
          look.set(Option.RIM, 0);
          look.set(Option.REFLECTION, 0);
          panel[0].invalidate();
        });
    SystemClock.sleep(180);
    Bitmap bent = copy();
    runOnMainSync(
        () -> {
          look.set(Option.REFRACTION, 0);
          panel[0].invalidate();
        });
    SystemClock.sleep(180);
    Bitmap flat = copy();
    int changed = 0;
    for (int y = 4; y < 28; y++)
      for (int x = 68; x < width - 60; x++) {
        int c = bent.getPixel(at[0] + x, at[1] + y), d = flat.getPixel(at[0] + x, at[1] + y);
        if (Math.abs(Color.green(c) - Color.green(d)) > 24) changed++;
      }
    // Also measure the bent vertical stripes on the left and right walls of the lens.
    for (int y = 40; y < height - 40; y++)
      for (int x = 5; x < 36; x++) {
        int rx = at[0] + width - x, lx = at[0] + x, py = at[1] + y;
        if (Math.abs(Color.green(bent.getPixel(rx, py)) - Color.green(flat.getPixel(rx, py))) > 24)
          changed++;
        if (Math.abs(Color.green(bent.getPixel(lx, py)) - Color.green(flat.getPixel(lx, py))) > 24)
          changed++;
      }
    if (requireLive || previous == Material.LIVE) {
      check(
          previous == Material.LIVE && changed > 75,
          "live refraction missing backend=" + previous + " pixels=" + changed);
    }
    Bitmap pair = Bitmap.createBitmap(width + 32, 2 * (height + 32), Bitmap.Config.ARGB_8888);
    Canvas out = new Canvas(pair);
    Rect src = new Rect(at[0] - 16, at[1] - 16, at[0] + width + 16, at[1] + height + 16);
    Rect dst = new Rect(0, 0, width + 32, height + 32);
    out.drawBitmap(green, src, dst, null);
    dst.offset(0, height + 32);
    out.drawBitmap(rose, src, dst, null);
    try (OutputStream stream =
        new FileOutputStream(new File(activity.getCacheDir(), "liquidshade-native.png"))) {
      check(pair.compress(Bitmap.CompressFormat.PNG, 100, stream), "native scene output");
    }
    return previous + "/" + changed;
  }

  private void testMediaSlots() {
    runOnMainSync(
        () -> {
          FrameLayout root = new FrameLayout(activity);
          ImageView a = new ImageView(activity),
              b = new ImageView(activity),
              reveal = new ImageView(activity);
          for (ImageView v : new ImageView[] {a, b, reveal}) {
            root.addView(v);
            v.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.RED));
          }
          activity.setContentView(root);
          MediaArtwork slots = new MediaArtwork(a, b, reveal);
          b.setVisibility(View.GONE);
          reveal.setVisibility(View.GONE);
          check(slots.current(root) == a, "primary artwork");
          a.setVisibility(View.GONE);
          b.setVisibility(View.VISIBLE);
          check(slots.current(root) == b, "alternate artwork");
          reveal.setVisibility(View.VISIBLE);
          check(slots.current(root) == null, "crossfade should keep a transparent edge");
        });
  }

  private void install() {
    Context c = activity;
    config = new Config();
    config.set(Option.PERFORMANCE, 0);
    config.set(Option.REDUCE, 0);
    config.set(Option.MOTION_AMOUNT, 100);
    config.set(Option.CONTRAST, 0);
    stage = new FrameLayout(c);
    stage.setBackgroundColor(0xff10253a);
    row =
        new FrameLayout(c) {
          @Override
          public boolean dispatchTouchEvent(MotionEvent ev) {
            if (glass != null) glass.rowTouch(background, ev);
            return super.dispatchTouchEvent(ev);
          }
        };
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(300, 110);
    lp.leftMargin = 18;
    lp.topMargin = 80;
    stage.addView(row, lp);
    row.setTranslationY(57);
    row.setOnClickListener(v -> clicks.incrementAndGet());
    background = new View(c);
    row.addView(background, new FrameLayout.LayoutParams(-1, -1));
    glass = new Material(background, stage, source, config, Material.Kind.CARD);
    glass.bindRow(row);
    glass.allowTexture = false;
    glass.events =
        new Material.Events() {
          public void drawn(Material m, int b) {
            backend = b;
          }

          public void motion() {}

          public void rowFrame() {
            rowFrames++;
          }
        };
    background.setBackground(glass);
    View icon = new View(c);
    icon.setBackgroundColor(Color.MAGENTA);
    FrameLayout.LayoutParams point = new FrameLayout.LayoutParams(32, 30);
    point.leftMargin = 12;
    point.topMargin = 32;
    row.addView(icon, point);
    activity.setContentView(stage);
  }

  private void pointer(int action, long down, float x, float y) {
    MotionEvent ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      // Dispatch exclusively into the test activity. The native window, row and click listener
      // still perform hit testing; unrelated emulator overlays never receive this pointer.
      runOnMainSync(() -> activity.getWindow().getDecorView().dispatchTouchEvent(ev));
    } finally {
      ev.recycle();
    }
  }

  private Bitmap copy() throws Exception {
    Bitmap[] output = new Bitmap[1];
    AtomicInteger result = new AtomicInteger(-1);
    CountDownLatch done = new CountDownLatch(1);
    runOnMainSync(
        () -> {
          Window window = activity.getWindow();
          View decor = window.getDecorView();
          output[0] =
              Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
          PixelCopy.request(
              window,
              output[0],
              i -> {
                result.set(i);
                done.countDown();
              },
              new Handler(Looper.getMainLooper()));
        });
    check(
        done.await(6, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS,
        "PixelCopy " + result.get());
    return output[0];
  }

  private static Rect marker(Bitmap b) {
    int[] line = new int[b.getWidth()];
    Rect r = new Rect();
    for (int y = 0; y < b.getHeight(); y++) {
      b.getPixels(line, 0, line.length, 0, y, line.length, 1);
      for (int x = 0; x < line.length; x++) {
        int c = line[x];
        if (Color.red(c) > 230 && Color.blue(c) > 230 && Color.green(c) < 30)
          r.union(x, y, x + 1, y + 1);
      }
    }
    return r;
  }
}
