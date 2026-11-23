package dev.vadinyt.liquidshade;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import java.io.IOException;
import java.lang.reflect.Method;

/** Samsung's wallpaper provider accepts a home/lock selector; no window or screen capture. */
final class HomeWallpaper {
  record Sample(Bitmap pixels, int status) {}

  private final Method frame =
      method(
          WallpaperManager.class,
          "semGetScreenshotFileDescriptor",
          int.class,
          int.class,
          Bundle.class);
  private final Method thumbnail =
      method(
          WallpaperManager.class, "semGetThumbnailFileDescriptor", int.class, int.class, int.class);
  private final Method userId = method(UserHandle.class, "getIdentifier");
  private final int phoneFlag = phoneFlag();

  private static int phoneFlag() {
    try {
      return WallpaperManager.class.getField("FLAG_DISPLAY_PHONE").getInt(null);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return 0;
    }
  }

  private static Method method(Class<?> owner, String name, Class<?>... args) {
    try {
      return owner.getMethod(name, args);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  static boolean unlocked(Context c) {
    UserManager users = c.getSystemService(UserManager.class);
    KeyguardManager key = c.getSystemService(KeyguardManager.class);
    return users != null
        && key != null
        && users.isUserUnlocked()
        && users.isUserForeground()
        && !key.isKeyguardLocked();
  }

  int homeFlag() {
    return WallpaperManager.FLAG_SYSTEM | phoneFlag;
  }

  static Bitmap decode(ParcelFileDescriptor descriptor, int edge) throws IOException {
    if (descriptor == null) return null;
    // ImageDecoder accepts both files and pipes. Its callable owns the duplicated FD.
    try (descriptor) {
      return BackdropPhoto.decode(
          ImageDecoder.createSource(
              () ->
                  new AssetFileDescriptor(
                      ParcelFileDescriptor.dup(descriptor.getFileDescriptor()),
                      0,
                      AssetFileDescriptor.UNKNOWN_LENGTH)),
          edge);
    }
  }

  private static Bitmap request(Method method, WallpaperManager wm, int edge, Object... args) {
    if (method == null || method.getReturnType() != ParcelFileDescriptor.class) return null;
    try {
      return decode((ParcelFileDescriptor) method.invoke(wm, args), edge);
    } catch (ReflectiveOperationException | IOException | RuntimeException unavailable) {
      return null;
    }
  }

  Sample read(Context ctx, WallpaperManager wm, int edge) {
    if (userId == null || !unlocked(ctx)) return null;
    int uid;
    try {
      uid = (int) userId.invoke(android.os.Process.myUserHandle());
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return null;
    }
    int which = homeFlag();
    Bitmap pixels = request(frame, wm, edge, which, uid, new Bundle());
    if (pixels != null) return new Sample(pixels, WallpaperSource.HOME_FRAME);
    if (!unlocked(ctx)) return null;
    int orientation =
        ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
            ? 1
            : 0;
    pixels = request(thumbnail, wm, edge, which, uid, orientation);
    return pixels == null ? null : new Sample(pixels, WallpaperSource.HOME_THUMB);
  }
}
