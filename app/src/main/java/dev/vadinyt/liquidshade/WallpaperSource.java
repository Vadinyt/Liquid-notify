package dev.vadinyt.liquidshade;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.Executor;

/** A bounded copy of the current user's static SYSTEM wallpaper. Never reads a window surface. */
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

  Bitmap forBlur(float spread, int viewport) {
    if (levels.length == 0 || image == null) return null;
    float step = (float) viewport / Math.max(1, image.getWidth());
    int i = 0;
    while (i + 1 < levels.length && step * 2 < spread) {
      i++;
      step *= 2;
    }
    return levels[i];
  }

  private int generation;
  float luminance = .45f;

  void clear() {
    generation++;
    image = null;
    levels = new Bitmap[0];
  }

  // This code runs with the host SystemUI UID. Its manifest declares READ_WALLPAPER_INTERNAL;
  // the call below also checks the current UID's grant. The companion never requests this grant.
  @android.annotation.SuppressLint("MissingPermission")
  void refresh(Context c, Executor worker, Handler main, int maxEdge, Runnable ready) {
    int token = ++generation;
    worker.execute(
        () -> {
          Bitmap result = null;
          float light = .45f;
          try {
            WallpaperManager wm = c.getSystemService(WallpaperManager.class);
            WallpaperColors colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors != null) light = colors.getPrimaryColor().luminance();
            if (c.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                && wm.getWallpaperInfo() == null) {
              try (ParcelFileDescriptor file = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
                if (file != null) {
                  BitmapFactory.Options opts = new BitmapFactory.Options();
                  opts.inJustDecodeBounds = true;
                  BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, opts);
                  if (opts.outWidth > 0 && opts.outHeight > 0) {
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = 1;
                    while (Math.max(opts.outWidth, opts.outHeight) / (long) opts.inSampleSize
                        > maxEdge) opts.inSampleSize *= 2;
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    result =
                        BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, opts);
                  }
                }
              }
            }
          } catch (RuntimeException | IOException | OutOfMemoryError unavailable) {
            result = null;
          }
          Bitmap[] filtered;
          try {
            filtered = pyramid(result);
          } catch (OutOfMemoryError low) {
            filtered = new Bitmap[0];
            result = null;
          }
          final Bitmap[] chain = filtered;
          final Bitmap next = result;
          final float level = light;
          main.post(
              () -> {
                if (token == generation) {
                  image = next;
                  levels = chain;
                  luminance = level;
                  ready.run();
                }
              });
        });
  }
}
