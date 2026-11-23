package dev.vadinyt.liquidshade;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import java.io.*;

/** One user-selected image, stored in credential-protected app storage. */
final class BackdropPhoto {
  private static AtomicFile file(Context c) {
    return new AtomicFile(new File(c.getFilesDir(), "shade-backdrop.webp"));
  }

  static synchronized boolean exists(Context c) {
    return file(c).getBaseFile().isFile();
  }

  static synchronized ParcelFileDescriptor open(Context c) throws FileNotFoundException {
    // Opening through AtomicFile also recovers an interrupted legacy write.
    try (FileInputStream in = file(c).openRead()) {
      return ParcelFileDescriptor.dup(in.getFD());
    } catch (IOException e) {
      throw new FileNotFoundException("No selected image");
    }
  }

  static Bitmap decode(ImageDecoder.Source src, int maxEdge) throws IOException {
    return ImageDecoder.decodeBitmap(
        src,
        (d, info, s) -> {
          int w = info.getSize().getWidth(), h = info.getSize().getHeight();
          double ratio = Math.min(1d, maxEdge / (double) Math.max(w, h));
          d.setTargetSize(Math.max(1, (int) (w * ratio)), Math.max(1, (int) (h * ratio)));
          d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
          d.setTargetColorSpace(
              android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
        });
  }

  static synchronized void replace(Context c, Uri uri) throws IOException {
    Bitmap b = decode(ImageDecoder.createSource(c.getContentResolver(), uri), 2160);
    AtomicFile f = file(c);
    FileOutputStream out = null;
    try {
      out = f.startWrite();
      if (!b.compress(Bitmap.CompressFormat.WEBP_LOSSY, 94, out)) throw new IOException("encode");
      f.finishWrite(out);
    } catch (IOException | RuntimeException e) {
      if (out != null) f.failWrite(out);
      throw e;
    } finally {
      b.recycle();
    }
    changed(c);
  }

  static synchronized void clear(Context c) {
    file(c).delete();
    changed(c);
  }

  private static void changed(Context c) {
    android.content.SharedPreferences p = Bridge.prefs(c);
    p.edit().putLong("photoRevision", p.getLong("photoRevision", 0) + 1).apply();
    c.getContentResolver().notifyChange(Bridge.CONFIG, null);
  }
}
