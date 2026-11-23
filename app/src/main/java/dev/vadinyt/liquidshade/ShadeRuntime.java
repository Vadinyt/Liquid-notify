package dev.vadinyt.liquidshade;

import static dev.vadinyt.liquidshade.Material.Kind.*;
import static dev.vadinyt.liquidshade.Option.*;

import android.app.*;
import android.content.*;
import android.content.res.*;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

final class ShadeRuntime
    implements View.OnAttachStateChangeListener,
        View.OnLayoutChangeListener,
        ViewTreeObserver.OnPreDrawListener,
        Material.Events {
  Field expanded, barState, qs, background, onLock, headsUp;
  Method child, groupedRows;
  Field normalBackground;
  boolean stackCompatible, cardCompatible, mediaCompatible, footerCompatible;
  int hooks;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService images = Executors.newSingleThreadExecutor();
  private final WallpaperSource wallpaper = new WallpaperSource();
  private final WeakHashMap<View, Binding> surfaces = new WeakHashMap<>();
  private final ArrayList<Binding> moving = new ArrayList<>();
  private final Map<String, Integer> ids = new HashMap<>();
  private WeakReference<View> stack = new WeakReference<>(null), stage = new WeakReference<>(null);
  private Context app;
  private Config cfg = new Config();
  private boolean connected,
      loaded,
      blocked,
      open,
      unlocked,
      requested,
      queued,
      reportQueued,
      customPhoto,
      hardPaused;
  private int localFailures, revision;
  private long lastFailure, photoRevision, lastBackdropRequest;
  private final android.graphics.Rect visibleRect = new android.graphics.Rect();
  private int openEpoch, openingRows, submittedFrames, headerDimension;
  private int bodyStart = Integer.MAX_VALUE;
  private float openingHeight;
  private long fullyVisibleAt, nextWake, heightChangedAt;
  Field panelTracking;
  private final int[] panelLocation = new int[2], stackLocation = new int[2];
  Field ambient, scrimTop;
  private Object stackState;
  private boolean signedHeight;
  private WeakReference<View> headerIcons = new WeakReference<>(null);
  private long openedAt, lastLog;
  private final Runnable revealFrame =
      () -> {
        nextWake = 0;
        View host = stack.get();
        if (open && host != null) host.postInvalidateOnAnimation();
      };
  java.util.function.Consumer<String> diagnostic;
  private int cardBackend, mediaBackend, sourceLast, lastMounted, motionEvents;
  private final WeakHashMap<View, WeakReference<View>> rowBackgrounds = new WeakHashMap<>();
  private KeyguardManager keyguard;

  void connect(Context context) {
    if (Looper.myLooper() != main.getLooper()) {
      main.post(() -> connect(context));
      return;
    }
    if (connected) return;
    app = context.getApplicationContext();
    if (app == null) return;
    keyguard = app.getSystemService(KeyguardManager.class);
    connected = true;
    unlocked = activeUserUnlocked();
    ContentObserver obs =
        new ContentObserver(main) {
          @Override
          public void onChange(boolean self, android.net.Uri uri) {
            if (Bridge.PING.equals(uri)) report();
            else read();
          }
        };
    app.getContentResolver().registerContentObserver(Bridge.CONFIG, false, obs);
    app.getContentResolver().registerContentObserver(Bridge.PING, false, obs);
    IntentFilter events = new IntentFilter();
    events.addAction(Intent.ACTION_SCREEN_OFF);
    events.addAction(Intent.ACTION_SCREEN_ON);
    events.addAction(Intent.ACTION_USER_PRESENT);
    events.addAction("android.intent.action.USER_SWITCHED");
    events.addAction(Intent.ACTION_WALLPAPER_CHANGED);
    events.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
    app.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent i) {
            unlocked = !Intent.ACTION_SCREEN_OFF.equals(i.getAction()) && activeUserUnlocked();
            if (!unlocked
                || "android.intent.action.USER_SWITCHED".equals(i.getAction())
                || Intent.ACTION_WALLPAPER_CHANGED.equals(i.getAction())
                || Intent.ACTION_CONFIGURATION_CHANGED.equals(i.getAction())) {
              wallpaper.clear();
              requested = false;
            }
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())
                || "android.intent.action.USER_SWITCHED".equals(i.getAction())) read();
            safeRefresh();
          }
        },
        events,
        null,
        main,
        Context.RECEIVER_NOT_EXPORTED);
    read();
  }

  private boolean activeUserUnlocked() {
    android.os.UserManager users = app.getSystemService(android.os.UserManager.class);
    return users != null
        && users.isUserForeground()
        && users.isUserUnlocked()
        && !keyguard.isKeyguardLocked();
  }

  private void read() {
    final int version = ++revision;
    worker.execute(
        () -> {
          try {
            Bundle b = app.getContentResolver().call(Bridge.CONFIG, "read", null, null);
            if (b == null) return;
            Config next = Config.decode(b.getString("config", "{}"));
            main.post(
                () -> {
                  if (version != revision) return;
                  boolean photo = b.getBoolean("photo");
                  long stamp = b.getLong("photoRevision");
                  if (next.get(PERFORMANCE) != cfg.get(PERFORMANCE)
                      || customPhoto != photo
                      || photoRevision != stamp) {
                    wallpaper.clear();
                    requested = false;
                  }
                  customPhoto = photo;
                  photoRevision = stamp;
                  cfg = next;
                  boolean previouslyBlocked = blocked;
                  blocked = b.getBoolean("blocked");
                  if (blocked) hardPaused = true;
                  else if (previouslyBlocked) {
                    localFailures = 0;
                    hardPaused = false;
                  }
                  loaded = true;
                  safeRefresh();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (version == revision) {
                    loaded = false;
                    safeRefresh();
                    main.postDelayed(
                        () -> {
                          if (!loaded && version == revision) read();
                        },
                        10000);
                  }
                });
          }
        });
  }

  void failure(Throwable error) {
    if (Looper.myLooper() != main.getLooper()) {
      main.post(() -> failure(error));
      return;
    }
    if (cfg.on(LOGGING))
      android.util.Log.w("LiquidShade", "Adapter failure: " + error.getClass().getSimpleName());
    long now = SystemClock.elapsedRealtime();
    if (hardPaused || (lastFailure != 0 && now - lastFailure < 250)) return;
    lastFailure = now;
    if (++localFailures >= 3) {
      hardPaused = true;
      main.post(this::restore);
    }
    if (app != null)
      worker.execute(
          () -> {
            try {
              app.getContentResolver().call(Bridge.CONFIG, "failure", null, null);
            } catch (RuntimeException ignored) {
            }
          });
  }

  private void restore() {
    moving.clear();
    for (Binding b : new ArrayList<>(surfaces.values()))
      try {
        b.dispose();
      } catch (RuntimeException ignored) {
      }
    report();
  }

  private void report() {
    if (app == null || reportQueued) return;
    reportQueued = true;
    main.postDelayed(
        () -> {
          reportQueued = false;
          int count = moving.size();
          if (open) lastMounted = Math.max(lastMounted, count);
          Bundle data = new Bundle();
          data.putInt("hooks", hooks);
          data.putInt("surfaces", count);
          data.putInt(
              "adapters",
              (stackCompatible ? 1 : 0)
                  | (cardCompatible ? 2 : 0)
                  | (mediaCompatible ? 4 : 0)
                  | (footerCompatible ? 8 : 0));
          data.putBoolean("open", open);
          data.putInt("lastMounted", lastMounted);
          data.putInt("cardBackend", cardBackend);
          data.putInt("mediaBackend", mediaBackend);
          data.putInt("source", sourceLast);
          data.putInt("motionEvents", motionEvents);
          data.putInt("rowFrames", submittedFrames);
          if (cfg.on(LOGGING)
              && diagnostic != null
              && SystemClock.elapsedRealtime() - lastLog > 1800) {
            lastLog = SystemClock.elapsedRealtime();
            diagnostic.accept(
                "1.4 source="
                    + sourceLast
                    + " card="
                    + cardBackend
                    + " open="
                    + open
                    + " peak="
                    + lastMounted
                    + " springs="
                    + motionEvents
                    + " rowDraws="
                    + submittedFrames);
          }
          worker.execute(
              () -> {
                try {
                  app.getContentResolver().call(Bridge.STATUS, "status", null, data);
                } catch (RuntimeException ignored) {
                }
              });
        },
        90);
  }

  @Override
  public void drawn(Material material, int backend) {
    if (material.kind == SHADE) {
      schedule();
      return;
    }
    if (material.kind == CARD && backend != cardBackend) {
      cardBackend = backend;
      report();
    }
    if (material.kind == MEDIA && backend != mediaBackend) {
      mediaBackend = backend;
      report();
    }
  }

  @Override
  public void motion() {
    motionEvents++;
    report();
  }

  @Override
  public void rowFrame() {
    submittedFrames++;
  }

  private int id(String name) {
    Integer saved = ids.get(name);
    if (saved != null) return saved;
    int result = app.getResources().getIdentifier(name, "id", ShadeModule.PKG);
    ids.put(name, result);
    return result;
  }

  private View named(View root, String name) {
    int i = id(name);
    return i == 0 ? null : root.findViewById(i);
  }

  private static boolean classIs(View v, String type) {
    for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass())
      if (c.getName().equals(type)) return true;
    return false;
  }

  private static View ancestor(View v, String type) {
    for (View a = v; a != null; a = a.getParent() instanceof View next ? next : null)
      if (classIs(a, type)) return a;
    return null;
  }

  void stack(View v) throws Exception {
    connect(v.getContext());
    if (v.getId() != id("notification_stack_scroller")
        || v.getDisplay() == null
        || v.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) return;
    View old = stack.get();
    if (old != v) {
      if (old != null) {
        old.getViewTreeObserver().removeOnPreDrawListener(this);
        old.removeOnAttachStateChangeListener(this);
      }
      View prior = stage.get();
      if (prior != null) drop(prior);
      stack = new WeakReference<>(v);
      View window = ancestor(v, ShadeModule.PKG + ".shade.NotificationShadeWindowView");
      View panel = window == null ? null : named(window, "notification_panel");
      boolean verified =
          panel != null
              && panel.getParent() == window
              && classIs(panel, ShadeModule.PKG + ".shade.NotificationPanelView")
              && (panel.getBackground() == null
                  || panel.getBackground() instanceof ColorDrawable fill
                      && Color.alpha(fill.getColor()) == 0);
      // A reparented stack may sit above a separate header. Its backing is allowed only with
      // a verified Samsung body boundary and a transparent original background.
      stage = new WeakReference<>(verified ? panel : v);
      bodyStart = Integer.MAX_VALUE;
      stackState = ambient == null ? null : ambient.get(v);
      boolean boundedStack =
          window != null
              && stackState != null
              && scrimTop != null
              && (v.getBackground() == null
                  || v.getBackground() instanceof ColorDrawable fill
                      && Color.alpha(fill.getColor()) == 0);
      if (verified || boundedStack) {
        int dim =
            app.getResources()
                .getIdentifier("large_screen_shade_header_height", "dimen", ShadeModule.PKG);
        headerDimension = dim == 0 ? 0 : v.getResources().getDimensionPixelSize(dim);
        headerIcons = new WeakReference<>(named(window, "shade_header_system_icons"));
        register(verified ? panel : v, SHADE);
      }
      v.addOnAttachStateChangeListener(this);
      v.getViewTreeObserver().addOnPreDrawListener(this);
    }
    gate(v);
    schedule();
  }

  void gate(View v) throws Exception {
    if (stack.get() != v || !stackCompatible) return;
    boolean next = expanded.getBoolean(v) && barState.getInt(v) == 0 && qs.getFloat(v) < .01f;
    if (next != open) {
      open = next;
      unlocked = activeUserUnlocked();
      main.removeCallbacks(revealFrame);
      nextWake = 0;
      openingHeight = 0;
      signedHeight = false;
      fullyVisibleAt = 0;
      if (!next) safeRefresh();
      else {
        openEpoch++;
        openingRows = 0;
        lastMounted = 0;
        openedAt = SystemClock.elapsedRealtime();
        if (v instanceof ViewGroup rows) {
          for (int i = 0; i < rows.getChildCount(); i++) {
            View direct = rows.getChildAt(i);
            if (classIs(direct, ShadeModule.ROW)) discoverRows(direct);
          }
        }
        wakeAt(openedAt + 220);
        if ((wallpaper.status == WallpaperSource.HOME_FRAME
                || wallpaper.status == WallpaperSource.LIVE)
            && openedAt - lastBackdropRequest > 4500) requested = false;
        if ((wallpaper.status == WallpaperSource.UNREADABLE
                || wallpaper.status == WallpaperSource.DENIED)
            && SystemClock.elapsedRealtime() - lastBackdropRequest > 60000) requested = false;
        schedule();
      }
    }
  }

  void expandedHeight(View view, float height) {
    if (open && view == stack.get() && Float.isFinite(height) && height > 0) {
      if (openingHeight != height) heightChangedAt = SystemClock.elapsedRealtime();
      signedHeight = true;
      openingHeight = height;
    }
  }

  private void wakeAt(long when) {
    if (nextWake != 0 && nextWake <= when) return;
    main.removeCallbacks(revealFrame);
    nextWake = when;
    main.postDelayed(revealFrame, Math.max(1, when - SystemClock.elapsedRealtime()));
  }

  // Use SystemUI's own notification/header touch boundary. A conservative status/date reserve
  // also protects heads-up transitions when Samsung reports a zero or stale scrim boundary.
  private void updateBody(View panel, View list) {
    if (panel == null || list == null) return;
    panel.getLocationOnScreen(panelLocation);
    list.getLocationOnScreen(stackLocation);
    WindowInsets insets = panel.getRootWindowInsets();
    if (insets == null || panel.getHeight() == 0) return;
    float dp = panel.getResources().getDisplayMetrics().density;
    int bars =
        insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout())
            .top;
    int bottom = Math.max(headerDimension, bars + Math.round(93 * dp)) - panelLocation[1];
    View icons = headerIcons.get();
    if (icons != null && icons.isShown() && icons.getGlobalVisibleRect(visibleRect))
      bottom = Math.max(bottom, visibleRect.bottom - panelLocation[1]);
    if (stackState != null && scrimTop != null) {
      try {
        float edge = scrimTop.getFloat(stackState);
        if (Float.isFinite(edge) && edge > 0)
          bottom = Math.max(bottom, Math.round(edge) + stackLocation[1] - panelLocation[1]);
      } catch (IllegalAccessException e) {
        stackState = null;
      }
    }
    int next = Math.min(panel.getHeight(), Math.max(0, bottom));
    if (bodyStart == next) return;
    bodyStart = next;
    for (int i = 0; i < moving.size(); i++) {
      Material glass = moving.get(i).glass;
      if (glass != null) glass.protectHeader(next);
    }
  }

  void row(View row) {
    connect(row.getContext());
    View bg = null;
    if (normalBackground != null) {
      try {
        bg = (View) normalBackground.get(row);
      } catch (IllegalAccessException ignored) {
      }
    }
    // The same id occurs in nested rows. Only style the background owned by this row.
    if (bg == null) bg = named(row, "backgroundNormal");
    if (bg == null
        || bg.getParent() != row
        || bg.getId() != id("backgroundNormal")
        || !classIs(bg, ShadeModule.BG)) return;
    WeakReference<View> old = rowBackgrounds.get(row);
    View previous = old == null ? null : old.get();
    if (previous != bg) {
      if (previous != null && (previous.getParent() == null || previous.getParent() == row))
        drop(previous);
      rowBackgrounds.put(row, new WeakReference<>(bg));
    }
    register(bg, CARD);
    if (loaded) applyOne(bg);
  }

  void discoverRows(View parent) {
    row(parent);
    if (groupedRows == null) return;
    try {
      Object children = groupedRows.invoke(parent);
      if (children instanceof List<?> rows) {
        for (Object item : rows)
          if (item instanceof View v && v != parent && classIs(v, ShadeModule.ROW)) row(v);
      }
    } catch (ReflectiveOperationException ignored) {
    }
  }

  void backgroundChanged(View v) {
    if (surfaces.containsKey(v)) {
      applyOne(v);
    } else if (v.getParent() instanceof View parent && classIs(parent, ShadeModule.ROW)) {
      row(parent);
    }
  }

  void footer(View root) {
    connect(root.getContext());
    for (String name : new String[] {"noti_setting", "clear_all"}) {
      View v = named(root, name);
      if (v instanceof TextView) register(v, Material.Kind.FOOTER);
    }
  }

  void media(View root, boolean notification) {
    connect(root.getContext());
    View art = named(root, "sec_album_art");
    if (art instanceof ImageView) {
      if (!notification) {
        Binding old = surfaces.get(art);
        if (old != null && old.kind == MEDIA) drop(art);
        return;
      }
      register(art, MEDIA);
      if (loaded) applyOne(art);
    }
  }

  void motion(View row, float push) {
    if (app == null || !open) return;
    WeakReference<View> ref = rowBackgrounds.get(row);
    Binding b = ref == null ? null : surfaces.get(ref.get());
    if (b != null && b.glass != null) b.glass.pulse(push);
  }

  void swipe(View row, float translation) {
    if (!open || !unlocked || hardPaused || !cfg.on(MOTION_ON)) return;
    WeakReference<View> ref = rowBackgrounds.get(row);
    Binding b = ref == null ? null : surfaces.get(ref.get());
    if (b == null || b.glass == null) return;
    b.glass.swipe(translation);
    float threshold = 12 * row.getResources().getDisplayMetrics().density;
    if (Math.abs(translation) < threshold * .25f) b.swipeStarted = false;
    else if (!b.swipeStarted && Math.abs(translation) > threshold) {
      b.swipeStarted = true;
      b.glass.pulse(.64f);
    }
  }

  void touch(View row, MotionEvent event) {
    if (!open || !unlocked || hardPaused || !cfg.on(PRESS) || !cfg.on(MOTION_ON)) return;
    WeakReference<View> ref = rowBackgrounds.get(row);
    View bg = ref == null ? null : ref.get();
    Binding b = bg == null ? null : surfaces.get(bg);
    if (b == null || b.glass == null) return;
    b.glass.rowTouch(bg, event);
  }

  private void register(View v, Material.Kind kind) {
    if (surfaces.containsKey(v)) return;
    surfaces.put(v, new Binding(v, kind));
    v.addOnAttachStateChangeListener(this);
    v.addOnLayoutChangeListener(this);
    schedule();
  }

  private void drop(View v) {
    Binding b = surfaces.remove(v);
    if (b != null) {
      moving.remove(b);
      b.dispose();
    }
    v.removeOnAttachStateChangeListener(this);
    v.removeOnLayoutChangeListener(this);
  }

  @Override
  public void onViewDetachedFromWindow(View v) {
    // Retain only the weak registration so the same row can reattach without reinflation.
    Binding b = surfaces.get(v);
    if (b != null) {
      moving.remove(b);
      b.dispose();
    }
    if (stack.get() == v) {
      v.getViewTreeObserver().removeOnPreDrawListener(this);
      open = false;
      stack.clear();
      stage.clear();
      stackState = null;
      headerIcons.clear();
      main.removeCallbacks(revealFrame);
      nextWake = 0;
      restore();
    }
  }

  @Override
  public void onViewAttachedToWindow(View v) {
    schedule();
  }

  @Override
  public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
    Binding s = surfaces.get(v);
    if (s != null && s.overlay && s.glass != null) s.glass.setBounds(0, 0, r - l, b - t);
  }

  private void schedule() {
    if (queued) return;
    queued = true;
    main.post(
        () -> {
          queued = false;
          safeRefresh();
        });
  }

  private void safeRefresh() {
    try {
      if (!loaded || blocked || hardPaused || !cfg.on(ENABLED) || !unlocked) {
        restore();
        if (wallpaper.image != null || requested) {
          wallpaper.clear();
          requested = false;
        }
        return;
      }
      View anchor = stage.get();
      if (open && anchor != null && cfg.on(SHADE_ON) && !requested) {
        requested = true;
        lastBackdropRequest = SystemClock.elapsedRealtime();
        wallpaper.refresh(
            anchor.getContext(),
            images,
            main,
            new int[] {1920, 1280, 720}[cfg.get(PERFORMANCE)],
            customPhoto,
            this::safeRefresh);
      }
      if (wallpaper.status != WallpaperSource.UNKNOWN) sourceLast = wallpaper.status;
      if (open) updateBody(anchor, stack.get());
      if (anchor != null) applyOne(anchor);
      for (View v : new ArrayList<>(surfaces.keySet())) if (v != null && v != anchor) applyOne(v);
      report();
    } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
      failure(e);
    }
  }

  private void applyOne(View v) {
    Binding b = surfaces.get(v);
    if (b == null) return;
    try {
      apply(v, b);
    } catch (Exception | LinkageError | OutOfMemoryError e) {
      moving.remove(b);
      b.uninstall();
      failure(e);
    }
  }

  private void apply(View v, Binding b) throws Exception {
    View host = stack.get(), frame = stage.get();
    boolean eligible =
        loaded
            && cfg.on(ENABLED)
            && !blocked
            && !hardPaused
            && unlocked
            && stackCompatible
            && host != null
            && frame != null
            && v.isAttachedToWindow()
            && v.getRootView() == host.getRootView()
            && barState.getInt(host) == 0
            && qs.getFloat(host) < .01f;
    boolean grouped = false, pinned = false;
    LayerDrawable layer = null;
    if (eligible && b.kind == CARD) {
      View row = ancestor(v, ShadeModule.ROW);
      eligible = cfg.on(CARDS_ON) && row != null && !onLock.getBoolean(v);
      if (eligible) {
        grouped = (boolean) child.invoke(row);
        pinned = headsUp.getBoolean(row);
        eligible = (open || pinned) && (!grouped || cfg.get(GROUPS) != 0);
        Object bg = background.get(v);
        if (bg instanceof LayerDrawable drawable
            && drawable.getNumberOfLayers() == 2
            && drawable.getId(0) == id("notification_focus_overlay")
            && drawable.getId(1) == id("radial_gradient_view")) layer = drawable;
        eligible &= layer != null;
      }
    } else if (eligible)
      eligible =
          open
              && switch (b.kind) {
                case SHADE -> cfg.on(SHADE_ON) && v == frame;
                case FOOTER ->
                    cfg.on(Option.FOOTER)
                        && ancestor(v, ShadeModule.PKG + ".statusbar.NotificationShelf") != null;
                case MEDIA -> cfg.on(MEDIA_ON);
                default -> false;
              };
    if (!eligible) {
      moving.remove(b);
      b.uninstall();
      return;
    }
    if (b.glass != null && (!b.owns(v) || b.layers != layer)) {
      moving.remove(b);
      b.uninstall();
    }
    if (b.glass == null) {
      Material glass =
          b.idle != null && b.idle.anchoredAt(frame)
              ? b.idle
              : new Material(v, frame, wallpaper, cfg, b.kind);
      b.idle = null;
      glass.events = this;
      glass.configure(cfg);
      if (layer != null) {
        Drawable orig = layer.getDrawable(0);
        if (orig == null
            || orig.getClass() != GradientDrawable.class
            || layer.getDrawable(1).getClass() != GradientDrawable.class) return;
        glass.setCornerRadii(((GradientDrawable) orig).getCornerRadii());
        glass.setAlpha(orig.getAlpha());
        glass.setBounds(orig.getBounds());
        glass.setState(orig.getState());
        b.original = orig;
        b.layers = layer;
        b.glass = glass;
        layer.setDrawable(0, glass);
        if (layer.getDrawable(0) == glass) {
          b.feedback = new RecoilFeedback((GradientDrawable) layer.getDrawable(1));
          layer.setDrawable(1, b.feedback);
        }
      } else if (b.kind == MEDIA) {
        b.glass = glass;
        b.overlay = true;
        glass.edgeOnly = true;
        glass.setBounds(0, 0, v.getWidth(), v.getHeight());
        v.getOverlay().add(glass);
      } else {
        Drawable orig = v.getBackground();
        if (orig != null
            && !(orig instanceof ColorDrawable
                || orig instanceof GradientDrawable
                || (b.kind == Material.Kind.FOOTER
                    && (orig instanceof RippleDrawable
                        || orig instanceof StateListDrawable
                        || orig instanceof InsetDrawable)))) return;
        b.original = orig;
        b.glass = glass;
        Binding.background(v, glass);
      }
      if (!b.owns(v)) {
        b.uninstall();
        return;
      }
      moving.add(b);
    }
    Material g = b.glass;
    g.configure(cfg);
    g.grouped = grouped;
    Binding back = surfaces.get(frame);
    g.allowTexture =
        open
            && cfg.on(SHADE_ON)
            && back != null
            && back.glass != null
            && back.owns(frame)
            && !back.glass.shaderFailed
            && (v == frame || back.glass.hasDrawnBackdrop() && sharesBackdrop(v, frame));
    g.protectHeader(bodyStart);
    if (b.kind == CARD) {
      View row = ancestor(v, ShadeModule.ROW);
      if (row != null) g.bindRow(row);
      View label = row == null ? null : row.findViewById(android.R.id.title);
      g.darkText =
          label instanceof TextView title
              ? Color.luminance(title.getCurrentTextColor()) < .38f
              : (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                  != Configuration.UI_MODE_NIGHT_YES;
    } else if (b.kind == MEDIA) {
      g.artwork = b.art((ImageView) v);
      View root = ancestor(v, ShadeModule.PKG + ".media.QSMediaCornerRoundedView");
      View progress = root == null ? null : named(root, "sec_media_progress_bar");
      int color = cfg.get(PROGRESS) == 2 && g.artwork != 0 ? g.artwork : cfg.get(HIGHLIGHT);
      if (progress instanceof ProgressBar p) b.setProgress(p, cfg.get(PROGRESS), color);
    }
    v.invalidate();
  }

  private boolean sharesBackdrop(View v, View panel) {
    if (ancestorView(v, panel)) return true;
    if (!(panel.getParent() instanceof ViewGroup window)) return false;
    View shared =
        ancestor(
            v,
            ShadeModule.PKG + ".statusbar.notification.stack.ui.view.SharedNotificationContainer");
    return shared != null
        && shared.getParent() == window
        && panel.getZ() <= shared.getZ()
        && window.indexOfChild(panel) < window.indexOfChild(shared);
  }

  private static boolean ancestorView(View v, View frame) {
    for (View p = v; p != null; p = p.getParent() instanceof View up ? up : null)
      if (p == frame) return true;
    return false;
  }

  @Override
  public boolean onPreDraw() {
    if (!open || moving.isEmpty()) return true;
    View panel = stage.get(), list = stack.get();
    if (panel == null || list == null) return true;
    updateBody(panel, list);
    long now = SystemClock.elapsedRealtime();
    int height = Math.max(1, list.getRootView().getHeight());
    if (fullyVisibleAt == 0) {
      boolean settled = signedHeight && now - heightChangedAt >= 280;
      if (settled && panelTracking != null) {
        try {
          settled = !panelTracking.getBoolean(list);
        } catch (IllegalAccessException unavailable) {
          settled = false;
        }
      }
      if (openingHeight >= height * .78f || settled || (!signedHeight && now - openedAt >= 380))
        fullyVisibleAt = now;
      else if (signedHeight && now - heightChangedAt < 280) wakeAt(heightChangedAt + 285);
    }
    // Recording translations can move an existing display list without re-recording it.
    // Invalidate only those lenses whose sample origin moved relative to the wallpaper.
    for (int i = 0; i < moving.size(); i++) {
      Binding b = moving.get(i);
      View v = b.view.get();
      Material m = b.glass;
      if (b.kind == CARD
          && b.revealEpoch != openEpoch
          && v != null
          && m != null
          && openingRows < 12
          && (fullyVisibleAt == 0 || now - fullyVisibleAt < 1150)
          && (signedHeight || now - openedAt >= 380)
          && v.isShown()
          && v.getGlobalVisibleRect(visibleRect)
          && visibleRect.height()
              >= Math.min(v.getHeight() / 2, 48 * v.getResources().getDisplayMetrics().density)) {
        float position = Math.max(0, Math.min(1, visibleRect.centerY() / (float) height));
        if (fullyVisibleAt != 0 || openingHeight >= height * Math.max(.42f, position * .85f)) {
          if (b.queuedEpoch != openEpoch) {
            b.queuedEpoch = openEpoch;
            b.revealAfter = now + Math.round(18 + 160 * position);
          }
          if (now >= b.revealAfter) {
            b.revealEpoch = openEpoch;
            openingRows++;
            m.reveal();
          } else wakeAt(b.revealAfter);
        }
      }
      if (!signedHeight && fullyVisibleAt == 0) wakeAt(openedAt + 385);
      if (b.kind == MEDIA && v instanceof ImageView art && b.artChanged(art)) schedule();
      if (v != null && m != null && v.isShown() && m.positionChanged()) v.invalidate();
    }
    return true;
  }
}
