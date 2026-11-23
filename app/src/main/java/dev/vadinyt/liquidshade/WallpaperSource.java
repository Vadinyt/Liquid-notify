package dev.vadinyt.liquidshade;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.Executor;

/** Optional, user-selected shade backing. Native mode needs no pixel source or cache. */
final class WallpaperSource {
  Bitmap image;
  Bitmap[] levels = new Bitmap[0];
  static final int UNKNOWN = 0, PHOTO = 2, DENIED = 4, UNREADABLE = 5, NATIVE = 8;
  int status = UNKNOWN;
  private volatile int generation;
  float luminance = .45f;

  void clear() {
    generation++;
    status = UNKNOWN;
    image = null;
    levels = new Bitmap[0];
    luminance = .45f;
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

  void loadPhoto(Context ui, Executor worker, Handler main, int maxEdge, Runnable ready) {
    int token = ++generation;
    worker.execute(
        () -> {
          if (token != generation) return;
          Bitmap result = null;
          int reason = UNREADABLE;
          try {
            result =
                BackdropPhoto.decode(
                    ImageDecoder.createSource(ui.getContentResolver(), Bridge.PHOTO), maxEdge);
            if (result != null) reason = PHOTO;
          } catch (SecurityException denied) {
            reason = DENIED;
          } catch (RuntimeException | IOException | OutOfMemoryError unavailable) {
            reason = UNREADABLE;
          }
          Bitmap[] filtered;
          try {
            if (token != generation) return;
            filtered = BlurPyramid.build(result);
          } catch (OutOfMemoryError | RuntimeException low) {
            filtered = new Bitmap[0];
            result = null;
            reason = UNREADABLE;
          }
          float light = .45f;
          if (result != null) {
            light = measureLight(filtered[filtered.length - 1]);
          }
          final Bitmap[] chain = filtered;
          final Bitmap next = result;
          final float level = light;
          final int why = reason;
          main.post(
              () -> {
                if (token == generation && ShadeAccess.unlocked(ui)) {
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
