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

  static final int UNKNOWN = 0, SYSTEM = 1, PHOTO = 2, LIVE = 3, DENIED = 4, UNREADABLE = 5;
  int status = UNKNOWN;
  private int generation;
  float luminance = .45f;

  void clear() {
    generation++;
    status = UNKNOWN;
    image = null;
    levels = new Bitmap[0];
  }

  // Samsung also hosts static image wallpapers through a service. Prefer the reported
  // wallpaper type when present (SEM_WALLPAPER_TYPE_IMAGE); fall back to Android's API.
  private static boolean isStatic(WallpaperManager wm) {
    try {
      int type =
          (int)
              WallpaperManager.class
                  .getMethod("semGetWallpaperType", int.class)
                  .invoke(wm, WallpaperManager.FLAG_SYSTEM);
      int imageType = WallpaperManager.class.getField("SEM_WALLPAPER_TYPE_IMAGE").getInt(null);
      if (type >= 0) return type == imageType;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return (Build.VERSION.SDK_INT >= 34
            ? wm.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
            : wm.getWallpaperInfo())
        == null;
  }

  private static Bitmap decode(ParcelFileDescriptor fd, int edge) throws IOException {
    if (fd == null) return null;
    BitmapFactory.Options opt = new BitmapFactory.Options();
    opt.inJustDecodeBounds = true;
    BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, opt);
    if (opt.outWidth <= 0 || opt.outHeight <= 0) return null;
    opt.inJustDecodeBounds = false;
    opt.inSampleSize = 1;
    while (Math.max(opt.outWidth, opt.outHeight) / (long) opt.inSampleSize > edge * 1.45)
      opt.inSampleSize *= 2;
    try {
      android.system.Os.lseek(fd.getFileDescriptor(), 0, android.system.OsConstants.SEEK_SET);
    } catch (android.system.ErrnoException e) {
      throw new IOException(e);
    }
    opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap decoded = BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, opt);
    if (decoded == null) return null;
    int max = Math.max(decoded.getWidth(), decoded.getHeight());
    if (max <= edge) return decoded;
    float ratio = edge / (float) max;
    Bitmap scaled =
        Bitmap.createScaledBitmap(
            decoded,
            Math.max(1, Math.round(decoded.getWidth() * ratio)),
            Math.max(1, Math.round(decoded.getHeight() * ratio)),
            true);
    if (decoded != scaled) decoded.recycle();
    return scaled;
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
              else if (!isStatic(wm)) reason = LIVE;
              else
                try (ParcelFileDescriptor f = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
                  result = decode(f, maxEdge);
                  if (result != null) reason = SYSTEM;
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
                if (token == generation) {
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
