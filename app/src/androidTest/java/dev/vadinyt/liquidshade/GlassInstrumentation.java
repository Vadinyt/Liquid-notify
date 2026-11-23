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
      testWallpaperPipe();
      Intent launch =
          new Intent(getTargetContext(), SettingsActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = startActivitySync(launch);
      runOnMainSync(this::install);
      waitForIdleSync();
      SystemClock.sleep(550);
      Rect before = marker(copy());
      check(before.width() > 15, "marker absent at rest: " + before);
      int[] at = new int[2];
      runOnMainSync(() -> row.getLocationOnScreen(at));
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
            source.levels = WallpaperSource.pyramid(bitmap);
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
      runOnMainSync(
          () -> {
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
              + " pipe=decoded");
      code = Activity.RESULT_OK;
    } catch (Throwable e) {
      android.util.Log.e("LiquidShadeTest", "Instrumentation failed", e);
      report.putString("stream", "FAIL " + e);
    } finally {
      if (activity != null) runOnMainSync(() -> activity.finish());
      finish(code, report);
    }
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
      sendPointerSync(ev);
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

  private static void testWallpaperPipe() throws Exception {
    Bitmap bitmap = Bitmap.createBitmap(74, 46, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(Color.BLUE);
    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
    try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "PNG");
    }
    Bitmap decoded = HomeWallpaper.decode(pipe[0], 37);
    check(
        decoded != null
            && decoded.getWidth() == 37
            && decoded.getHeight() == 23
            && Color.blue(decoded.getPixel(1, 1)) > 250,
        "non-seekable decode");
  }
}
