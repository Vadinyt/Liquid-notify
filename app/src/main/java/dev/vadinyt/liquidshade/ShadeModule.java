package dev.vadinyt.liquidshade;

import android.app.Application;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;

public final class ShadeModule extends XposedModule {
  static final String PKG = "com.android.systemui";
  static final String ROW = PKG + ".statusbar.notification.row.ExpandableNotificationRow";
  static final String BG = PKG + ".statusbar.notification.row.NotificationBackgroundView";
  static final String STACK = PKG + ".statusbar.notification.stack.NotificationStackScrollLayout";
  private final ShadeRuntime runtime = new ShadeRuntime();

  private interface After {
    void run(Object receiver) throws Exception;
  }

  private void after(Class<?> type, String name, After action, Class<?>... args)
      throws NoSuchMethodException {
    Method m = type.getDeclaredMethod(name, args);
    if (m.getReturnType() != void.class) throw new NoSuchMethodException(name);
    hook(m)
        .intercept(
            chain -> {
              Object result = chain.proceed();
              try {
                action.run(chain.getThisObject());
              } catch (Exception | LinkageError | OutOfMemoryError err) {
                runtime.failure(err);
              }
              return result;
            });
    runtime.hooks++;
  }

  private void onExpansionChange(Class<?> cls, String method, String fieldName, Class<?>... args)
      throws ReflectiveOperationException {
    Field flag = field(cls, fieldName, boolean.class);
    Method m = cls.getDeclaredMethod(method, args);
    if (m.getReturnType() != void.class) return;
    hook(m)
        .intercept(
            c -> {
              View row = (View) c.getThisObject();
              boolean before;
              try {
                before = flag.getBoolean(row);
              } catch (IllegalAccessException e) {
                return c.proceed();
              }
              Object result = c.proceed();
              try {
                boolean after = flag.getBoolean(row);
                if (before != after) runtime.motion(row, after ? .84f : -.78f);
              } catch (IllegalAccessException
                  | RuntimeException
                  | LinkageError
                  | OutOfMemoryError e) {
                runtime.failure(e);
              }
              return result;
            });
    runtime.hooks++;
  }

  static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
    Field field = type.getDeclaredField(name);
    if (!expected.isAssignableFrom(field.getType())) throw new NoSuchFieldException(name);
    field.setAccessible(true);
    return field;
  }

  @Override
  public void onPackageReady(PackageReadyParam param) {
    if (!PKG.equals(param.getPackageName())
        || !PKG.equals(Application.getProcessName())
        || !param.isFirstPackage()
        || Build.VERSION.SDK_INT != 37
        || !"samsung".equalsIgnoreCase(Build.MANUFACTURER)) return;
    ClassLoader cl = param.getClassLoader();
    runtime.diagnostic = msg -> log(android.util.Log.INFO, "LiquidShade", msg);
    // Each adapter is optional. An unknown signature leaves that surface to Samsung.
    try {
      Class<?> s = cl.loadClass(STACK);
      runtime.expanded = field(s, "mIsExpanded", boolean.class);
      runtime.barState = field(s, "mStatusBarState", int.class);
      runtime.qs = field(s, "mQsExpansionFraction", float.class);
      after(s, "onAttachedToWindow", v -> runtime.stack((View) v));
      after(s, "setStatusBarState", v -> runtime.gate((View) v), int.class);
      after(s, "setIsExpanded", v -> runtime.gate((View) v), boolean.class);
      after(s, "setQsExpansionFraction", v -> runtime.gate((View) v), float.class);
      runtime.stackCompatible = true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
    }
    try {
      Class<?> r = cl.loadClass(ROW), b = cl.loadClass(BG);
      Class<?> recoil = cl.loadClass("androidx.appcompat.graphics.drawable.SeslRecoilDrawable");
      runtime.background = field(b, "mBackground", recoil);
      runtime.onLock = field(b, "mOnKeyguard", boolean.class);
      runtime.headsUp = field(r, "mIsHeadsUp", boolean.class);
      runtime.child = r.getDeclaredMethod("isChildInGroup");
      after(r, "onAttachedToWindow", v -> runtime.row((View) v));
      after(r, "setOnKeyguard", v -> runtime.row((View) v), boolean.class);
      after(r, "updateBackgroundForGroupState", v -> runtime.row((View) v));
      after(b, "setCustomBackground", v -> runtime.backgroundChanged((View) v), recoil);
      runtime.cardCompatible = true;
      onExpansionChange(r, "setUserExpanded", "mUserExpanded", boolean.class, boolean.class);
      onExpansionChange(r, "setChildrenExpanded", "mChildrenExpanded", boolean.class);
      Method swipe = r.getDeclaredMethod("setTranslation", float.class);
      if (swipe.getReturnType() == void.class) {
        hook(swipe)
            .intercept(
                c -> {
                  Object result = c.proceed();
                  try {
                    runtime.swipe((View) c.getThisObject(), (float) c.getArg(0));
                  } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
                    runtime.failure(e);
                  }
                  return result;
                });
        runtime.hooks++;
      }
      after(r, "performDismiss", v -> runtime.motion((View) v, -.65f), boolean.class);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
    }
    try {
      Class<?> activatable =
          cl.loadClass(PKG + ".statusbar.notification.row.ActivatableNotificationView");
      Method dispatch = activatable.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
      if (runtime.cardCompatible && dispatch.getReturnType() == boolean.class) {
        hook(dispatch)
            .intercept(
                chain -> {
                  try {
                    runtime.touch((View) chain.getThisObject(), (MotionEvent) chain.getArg(0));
                  } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
                    runtime.failure(e);
                  }
                  return chain.proceed();
                });
        runtime.hooks++;
      }
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
    }
    try {
      String media = PKG + ".media.";
      Class<?> p = cl.loadClass(media + "SecMediaControlPanel"),
          holder = cl.loadClass(media + "SecPlayerViewHolder");
      Field type = field(p, "mType", cl.loadClass(media + "MediaType")),
          viewHolder = field(p, "mViewHolder", holder);
      Field player = field(holder, "playerView", View.class);
      After bind =
          panel -> {
            Object h = viewHolder.get(panel), t = type.get(panel);
            if (h != null && t instanceof Enum<?> variant) {
              View root = (View) player.get(h);
              if (root != null)
                runtime.media(root, "ENR".equals(variant.name()) || "OA".equals(variant.name()));
            }
          };
      after(p, "attach", bind, holder);
      after(
          p,
          "bind",
          bind,
          cl.loadClass(media + "controls.shared.model.MediaData"),
          String.class,
          boolean.class);
      runtime.mediaCompatible = true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
    }
    try {
      after(
          cl.loadClass(PKG + ".statusbar.NotificationShelf"),
          "onFinishInflate",
          v -> runtime.footer((View) v));
      runtime.footerCompatible = true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
    }
    // Process liveness is independent of successful Samsung adapters.
    try {
      after(
          cl.loadClass(PKG + ".SystemUIApplication"),
          "onCreate",
          v -> runtime.connect((Application) v));
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
    }
  }
}
