package dev.vadinyt.liquidshade;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.Executor;

/**
 * A bounded shade backing selected from the current user’s home wallpaper or chosen photo. Never
 * reads a window surface.
 */
final class WallpaperSource {
  Bitmap image;
  Bitmap[] levels = new Bitmap[0];

  static Bitmap[] pyramid(Bitmap image) {
    if (image == null) return new Bitmap[0];
    Bitmap[] out = new Bitmap[6];
    out[0] = image;
    for (int i = 1; i < out.length; i++) {
      Bitmap last = out[i - 1];
      out[i] =
          Bitmap.createScaledBitmap(
              last, Math.max(1, last.getWidth() / 2), Math.max(1, last.getHeight() / 2), true);
    }
    return out;
  }

  Bitmap forBlur(float spread, int width, int height) {
    if (levels.length == 0 || image == null) return null;
    float step = Math.max(width / (float) image.getWidth(), height / (float) image.getHeight());
    int i = 0;
    while (i + 1 < levels.length && step * 2 < spread) {
      i++;
      step *= 2;
    }
    return levels[i];
  }

  static final int UNKNOWN = 0,
      SYSTEM = 1,
      PHOTO = 2,
      LIVE = 3,
      DENIED = 4,
      UNREADABLE = 5,
      HOME_FRAME = 6,
      HOME_THUMB = 7;
  private final HomeWallpaper home = new HomeWallpaper();
  int status = UNKNOWN;
  private volatile int generation;
  float luminance = .45f;

  void clear() {
    generation++;
    status = UNKNOWN;
    image = null;
    levels = new Bitmap[0];
  }

  // Samsung also hosts static image wallpapers through a service. Prefer the reported
  // wallpaper type when present (SEM_WALLPAPER_TYPE_IMAGE); fall back to Android's API.
  private static boolean isStatic(WallpaperManager wm, int which) {
    try {
      int type =
          (int)
              WallpaperManager.class.getMethod("semGetWallpaperType", int.class).invoke(wm, which);
      int imageType = WallpaperManager.class.getField("SEM_WALLPAPER_TYPE_IMAGE").getInt(null);
      if (type >= 0) return type == imageType;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return (Build.VERSION.SDK_INT >= 34
            ? wm.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
            : wm.getWallpaperInfo())
        == null;
  }

  static float measureLight(Bitmap b) {
    float sum = 0;
    for (int x = 0; x < 4; x++)
      for (int y = 0; y < 4; y++)
        sum +=
            Color.luminance(
                b.getPixel((2 * x + 1) * b.getWidth() / 8, (2 * y + 1) * b.getHeight() / 8));
    return sum / 16;
  }

  // Executed under the SystemUI UID and guarded by its runtime permission grant.
  @android.annotation.SuppressLint("MissingPermission")
  void refresh(
      Context ui, Executor worker, Handler main, int maxEdge, boolean custom, Runnable ready) {
    int token = ++generation;
    worker.execute(
        () -> {
          if (token != generation) return;
          Bitmap result = null;
          int reason = UNREADABLE;
          try {
            if (custom) {
              result =
                  BackdropPhoto.decode(
                      ImageDecoder.createSource(ui.getContentResolver(), Bridge.PHOTO), maxEdge);
              if (result != null) reason = PHOTO;
            } else {
              WallpaperManager wm = ui.getSystemService(WallpaperManager.class);
              if (ui.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL")
                  != android.content.pm.PackageManager.PERMISSION_GRANTED) reason = DENIED;
              else if (HomeWallpaper.unlocked(ui)) {
                boolean fixed = false;
                try {
                  fixed = isStatic(wm, home.homeFlag());
                } catch (RuntimeException ignored) {
                }
                if (fixed) {
                  try {
                    result =
                        HomeWallpaper.decode(
                            wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM), maxEdge);
                  } catch (IOException | IllegalArgumentException unavailable) {
                  }
                }
                if (result != null) reason = SYSTEM;
                else {
                  HomeWallpaper.Sample capture = home.read(ui, wm, maxEdge);
                  if (capture != null) {
                    result = capture.pixels();
                    reason = capture.status();
                  } else reason = fixed ? UNREADABLE : LIVE;
                }
              }
            }
          } catch (SecurityException denied) {
            reason = DENIED;
          } catch (RuntimeException | IOException | OutOfMemoryError unavailable) {
            reason = UNREADABLE;
          }
          Bitmap[] filtered;
          try {
            filtered = pyramid(result);
          } catch (OutOfMemoryError low) {
            filtered = new Bitmap[0];
            result = null;
            reason = UNREADABLE;
          }
          float light = .45f;
          if (result != null) {
            light = measureLight(filtered[filtered.length - 1]);
          } else
            try {
              WallpaperColors wc =
                  ui.getSystemService(WallpaperManager.class)
                      .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
              if (wc != null) light = wc.getPrimaryColor().luminance();
            } catch (RuntimeException ignored) {
            }
          final Bitmap[] chain = filtered;
          final Bitmap next = result;
          final float level = light;
          final int why = reason;
          main.post(
              () -> {
                if (token == generation && HomeWallpaper.unlocked(ui)) {
                  image = next;
                  levels = chain;
                  luminance = level;
                  status = why;
                  ready.run();
                }
              });
        });
  }
}
