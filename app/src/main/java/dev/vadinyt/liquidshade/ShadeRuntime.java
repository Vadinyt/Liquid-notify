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
        ViewTreeObserver.OnPreDrawListener {
  Field expanded, barState, qs, background, onLock, headsUp;
  Method child;
  boolean stackCompatible, cardCompatible, mediaCompatible, footerCompatible;
  int hooks;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
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
      lastLens,
      hardPaused;
  private int localFailures, revision;
  private long lastFailure;
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
    unlocked = !keyguard.isKeyguardLocked();
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
    events.addAction(Intent.ACTION_WALLPAPER_CHANGED);
    events.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
    app.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent i) {
            unlocked =
                !Intent.ACTION_SCREEN_OFF.equals(i.getAction()) && !keyguard.isKeyguardLocked();
            if (!unlocked
                || Intent.ACTION_WALLPAPER_CHANGED.equals(i.getAction())
                || Intent.ACTION_CONFIGURATION_CHANGED.equals(i.getAction())) {
              wallpaper.clear();
              requested = false;
            }
            safeRefresh();
          }
        },
        events,
        null,
        main,
        Context.RECEIVER_NOT_EXPORTED);
    read();
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
                  if (next.get(PERFORMANCE) != cfg.get(PERFORMANCE)) {
                    wallpaper.clear();
                    requested = false;
                  }
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
          boolean lens = false;
          int count = 0;
          for (Binding b : moving)
            if (b.glass != null) {
              count++;
              if (b.glass.drewTexture) lens = true;
            }
          if (count > 0) lastLens = lens;
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
          data.putBoolean("lens", lastLens);
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

  private static View ancestor(View v, String type) {
    for (View a = v; a != null; a = a.getParent() instanceof View next ? next : null)
      if (a.getClass().getName().equals(type)) return a;
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
      View frame = ancestor(v, ShadeModule.PKG + ".shade.NotificationPanelView");
      if (frame == null) frame = v;
      stage = new WeakReference<>(frame);
      register(frame, SHADE);
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
      unlocked = !keyguard.isKeyguardLocked();
      if (!next) safeRefresh();
      else schedule();
    }
  }

  void row(View row) {
    connect(row.getContext());
    View bg = named(row, "backgroundNormal");
    if (bg == null || !bg.getClass().getName().equals(ShadeModule.BG)) return;
    register(bg, CARD);
    if (loaded) applyOne(bg);
  }

  void backgroundChanged(View v) {
    if (surfaces.containsKey(v)) applyOne(v);
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
    Binding b = surfaces.get(named(row, "backgroundNormal"));
    if (b != null && b.glass != null) b.glass.pulse(push);
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
        wallpaper.refresh(
            app,
            worker,
            main,
            new int[] {1920, 1280, 720}[cfg.get(PERFORMANCE)],
            this::safeRefresh);
      }
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
      glass.cfg = cfg;
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
    g.cfg = cfg;
    g.grouped = grouped;
    Binding back = surfaces.get(frame);
    g.allowTexture =
        open
            && cfg.on(SHADE_ON)
            && back != null
            && back.glass != null
            && back.owns(frame)
            && !back.glass.shaderFailed
            && (v == frame || ancestorView(v, frame));
    if (b.kind == CARD) {
      View row = ancestor(v, ShadeModule.ROW);
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

  private static boolean ancestorView(View v, View frame) {
    for (View p = v; p != null; p = p.getParent() instanceof View up ? up : null)
      if (p == frame) return true;
    return false;
  }

  @Override
  public boolean onPreDraw() {
    if (!open || moving.isEmpty()) return true;
    // Recording translations can move an existing display list without re-recording it.
    // Invalidate only those lenses whose sample origin moved relative to the wallpaper.
    for (int i = 0; i < moving.size(); i++) {
      Binding b = moving.get(i);
      View v = b.view.get();
      Material m = b.glass;
      if (b.kind == MEDIA && v instanceof ImageView art && b.artChanged(art)) schedule();
      if (v != null && m != null && m.allowTexture && v.isShown() && m.positionChanged())
        v.invalidate();
    }
    return true;
  }
}
