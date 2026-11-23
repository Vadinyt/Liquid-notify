package dev.vadinyt.liquidshade;

import static dev.vadinyt.liquidshade.Option.*;

import android.app.*;
import android.content.*;
import android.content.res.*;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class SettingsActivity extends Activity {
  private static final int BG = 0xff10151b,
      PANEL = 0xff1b232b,
      WHITE = 0xffedf3f6,
      MUTED = 0xffa7b7be,
      ICE = 0xff97dbea;
  private static final int[] SECTIONS = {
    R.string.global,
    R.string.optics,
    R.string.cards,
    R.string.media,
    R.string.shade,
    R.string.motion,
    R.string.advanced
  };
  private static final int[] PRESETS = {
    R.string.clear, R.string.balanced, R.string.frosted, R.string.dark, R.string.custom
  };
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable persist = () -> Bridge.save(this, cfgValue());
  private final Runnable probe = () -> refreshStatus();
  private final ContentObserver observer =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean self) {
          refreshStatus();
        }
      };
  private Config cfg;
  private Preview preview;
  private LinearLayout rows, nav;
  private TextView status;
  private ScrollView list;
  private int section;
  private long pingAt;

  private Config cfgValue() {
    return cfg;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    cfg = Bridge.load(this);
    if (state != null) section = state.getInt("section", 0);
    LinearLayout root = column();
    root.setBackgroundColor(BG);
    root.setPadding(dp(16), 0, dp(16), 0);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets a =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(dp(16) + a.left, a.top, dp(16) + a.right, a.bottom);
          return insets;
        });
    getWindow().setDecorFitsSystemWindows(false);
    setContentView(root);
    TextView title = text("LiquidShade", 30, WHITE);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    root.addView(title);
    title.setPadding(0, dp(8), 0, dp(6));
    preview = new Preview(this, cfg);
    preview.setBackground(shape(PANEL, 19));
    root.addView(
        preview,
        new LinearLayout.LayoutParams(
            -1,
            dp(
                getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                    ? 68
                    : 128)));
    HorizontalScrollView sc = new HorizontalScrollView(this);
    sc.setHorizontalScrollBarEnabled(false);
    nav = new LinearLayout(this);
    sc.addView(nav);
    root.addView(sc);
    list = new ScrollView(this);
    list.setFillViewport(false);
    rows = column();
    list.addView(rows);
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    preview.reloadPhoto();
    getContentResolver().registerContentObserver(Bridge.STATUS, false, observer);
    ping();
  }

  @Override
  protected void onPause() {
    getContentResolver().unregisterContentObserver(observer);
    main.removeCallbacks(probe);
    main.removeCallbacks(persist);
    Bridge.save(this, cfg);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("section", section);
  }

  private int dp(float x) {
    return Math.round(getResources().getDisplayMetrics().density * x);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private TextView text(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setGravity(Gravity.CENTER_VERTICAL);
    return t;
  }

  private Button button(String s, Runnable click) {
    Button b = new Button(this);
    b.setAllCaps(false);
    b.setText(s);
    b.setTextColor(WHITE);
    b.setBackgroundTintList(ColorStateList.valueOf(0xff253540));
    b.setOnClickListener(v -> click.run());
    return b;
  }

  private void command(int label, Runnable act) {
    rows.addView(button(getString(label), act));
  }

  private void render() {
    rows.removeAllViews();
    nav.removeAllViews();
    status = null;
    list.scrollTo(0, 0);
    for (int i = 0; i < SECTIONS.length; i++) {
      final int j = i;
      Button b =
          button(
              getString(SECTIONS[i]),
              () -> {
                section = j;
                render();
                if (j == 6) ping();
              });
      b.setTextSize(13);
      b.setTextColor(i == section ? BG : MUTED);
      b.setBackgroundTintList(ColorStateList.valueOf(i == section ? ICE : PANEL));
      nav.addView(b);
      if (i == section)
        b.post(
            () ->
                ((HorizontalScrollView) nav.getParent())
                    .smoothScrollTo(Math.max(0, b.getLeft() - dp(20)), 0));
    }
    preview.mode =
        section == 3
            ? Material.Kind.MEDIA
            : section == 4 ? Material.Kind.FOOTER : Material.Kind.CARD;
    preview.reloadPhoto();
    preview.config(cfg);
    if (section == 0)
      choice(
          getString(R.string.presets),
          PRESETS,
          cfg.preset,
          index -> {
            cfg.select(index);
            changed();
            render();
          });
    if (section == 1 || section == 4) {
      TextView hint =
          text(getString(section == 1 ? R.string.optics_hint : R.string.photo_hint), 14, MUTED);
      hint.setPadding(dp(8), dp(8), dp(8), dp(12));
      rows.addView(hint);
    }
    if (section == 4) {
      command(
          R.string.choose_photo,
          () ->
              startActivityForResult(
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .setType("image/*"),
                  63));
      if (BackdropPhoto.exists(this))
        command(
            R.string.clear_photo,
            () -> {
              BackdropPhoto.clear(this);
              render();
            });
    }
    if (section == 5) command(R.string.replay, preview::replay);
    for (Option o : Config.OPTIONS) if (o.section == SECTIONS[section]) control(o);
    if (section == 0)
      command(
          R.string.reset,
          () ->
              confirm(
                  R.string.reset_msg,
                  () -> {
                    cfg = new Config();
                    changed();
                    render();
                  }));
    if (section == 6) {
      status = text("", 15, MUTED);
      status.setPadding(dp(8), dp(12), dp(8), dp(12));
      rows.addView(status);
      refreshStatus();
      command(R.string.export_json, () -> document(true));
      command(R.string.import_json, () -> document(false));
      if (Bridge.prefs(this).getInt("failures", 0) >= 3)
        command(
            R.string.retry,
            () -> {
              Bridge.prefs(this).edit().putInt("failures", 0).apply();
              Bridge.save(this, cfg);
              render();
              ping();
            });
      command(R.string.restart, () -> confirm(R.string.restart_msg, this::restart));
    }
  }

  private void edited(Option o, int value) {
    cfg.set(o, value);
    if (o.section == R.string.optics
        || o.section == R.string.cards
        || o.section == R.string.media
        || o.section == R.string.shade) cfg.preset = 4;
    changed();
  }

  private void changed() {
    preview.config(cfg);
    main.removeCallbacks(persist);
    main.postDelayed(persist, 95);
  }

  private LinearLayout block() {
    LinearLayout b = column();
    b.setBackground(shape(PANEL, 13));
    b.setPadding(dp(14), dp(6), dp(14), dp(6));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(5);
    rows.addView(b, lp);
    return b;
  }

  private interface Pick {
    void set(int value);
  }

  private void choice(String label, int[] options, int value, Pick callback) {
    Button b =
        button(
            label + "  ·  " + getString(options[value]),
            () -> {
              String[] names = new String[options.length];
              for (int i = 0; i < names.length; i++) names[i] = getString(options[i]);
              new AlertDialog.Builder(this)
                  .setTitle(label)
                  .setSingleChoiceItems(
                      names,
                      value,
                      (d, which) -> {
                        d.dismiss();
                        callback.set(which);
                      })
                  .show();
            });
    rows.addView(b);
  }

  private void control(Option o) {
    String label = getString(o.label);
    int value = cfg.get(o);
    if (o.maximum == 2) {
      int[] opts =
          o == PERFORMANCE
              ? new int[] {R.string.quality, R.string.balanced, R.string.battery}
              : o == GROUPS
                  ? new int[] {R.string.stock, R.string.unified, R.string.separate}
                  : new int[] {R.string.stock, R.string.accent, R.string.art};
      choice(
          label,
          opts,
          value,
          i -> {
            edited(o, i);
            render();
          });
      return;
    }
    if (o.maximum < 0) {
      Button b = button(label + "  ·  " + hex(value), () -> colour(o));
      b.setTextColor(value);
      rows.addView(b);
      return;
    }
    LinearLayout group = block();
    if (o.maximum == 1) {
      Switch s = new Switch(this);
      s.setText(label);
      s.setChecked(value == 1);
      s.setTextColor(WHITE);
      s.setMinHeight(dp(44));
      group.addView(s);
      s.setOnCheckedChangeListener((v, checked) -> edited(o, checked ? 1 : 0));
      return;
    }
    LinearLayout line = new LinearLayout(this);
    group.addView(line);
    TextView name = text(label, 15, WHITE), number = text(String.valueOf(value), 14, ICE);
    line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    line.addView(number);
    SeekBar slider = new SeekBar(this);
    slider.setMax(o.maximum);
    slider.setProgress(value);
    slider.setContentDescription(label);
    slider.setMinimumHeight(dp(36));
    group.addView(slider);
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}

          public void onProgressChanged(SeekBar s, int v, boolean user) {
            if (user) {
              number.setText(String.valueOf(v));
              edited(o, v);
            }
          }
        });
  }

  private static String hex(int color) {
    return String.format(Locale.ROOT, "#%06X", color & 0xffffff);
  }

  private void colour(Option o) {
    EditText entry = new EditText(this);
    entry.setSingleLine();
    entry.setHint(R.string.hex);
    entry.setInputType(InputType.TYPE_CLASS_TEXT);
    entry.setText(hex(cfg.get(o)));
    entry.selectAll();
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(o.label)
            .setView(entry)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply, null)
            .create();
    d.setOnShowListener(
        x ->
            d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String val = entry.getText().toString().trim();
                      if (!val.matches("#[0-9a-fA-F]{6}")) {
                        entry.setError(getString(R.string.hex));
                        return;
                      }
                      edited(o, Color.parseColor(val));
                      d.dismiss();
                      render();
                    }));
    d.show();
  }

  private void confirm(int message, Runnable act) {
    new AlertDialog.Builder(this)
        .setMessage(message)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.apply, (d, w) -> act.run())
        .show();
  }

  private void ping() {
    pingAt = SystemClock.elapsedRealtime();
    getContentResolver().notifyChange(Bridge.PING, null);
    refreshStatus();
    main.removeCallbacks(probe);
    main.postDelayed(probe, 2500);
  }

  private void refreshStatus() {
    if (status == null) return;
    SharedPreferences p = Bridge.prefs(this);
    boolean live = p.getLong("stamp", 0) >= pingAt && pingAt > 0;
    String state =
        getString(
            live
                ? R.string.active
                : SystemClock.elapsedRealtime() - pingAt > 2000
                    ? R.string.offline
                    : R.string.waiting);
    if (live) {
      int mask = p.getInt("adapters", 0);
      if ((mask & 1) == 0) state += "\n" + getString(R.string.no_hooks);
      else {
        int[] names = {R.string.shade, R.string.cards, R.string.media, R.string.footer};
        for (int i = 0; i < 4; i++)
          state +=
              "\n"
                  + getString(names[i])
                  + "  ·  "
                  + getString((mask & (1 << i)) != 0 ? R.string.yes : R.string.no);
      }
      state +=
          "\n"
              + getString(R.string.installed)
              + "  "
              + p.getInt("hooks", 0)
              + "   ·   "
              + getString(R.string.surfaces)
              + "  "
              + p.getInt("surfaces", 0)
              + "  ·  "
              + getString(R.string.last_mount)
              + "  "
              + p.getInt("lastMounted", 0);
      state +=
          "\n"
              + getString(R.string.backdrop)
              + "  ·  "
              + getString(
                  switch (p.getInt("source", 0)) {
                    case WallpaperSource.SYSTEM -> R.string.source_system;
                    case WallpaperSource.PHOTO -> R.string.source_photo;
                    case WallpaperSource.LIVE -> R.string.source_live;
                    case WallpaperSource.DENIED -> R.string.source_denied;
                    case WallpaperSource.UNREADABLE -> R.string.source_unreadable;
                    default -> R.string.unseen;
                  });
      state += "\n" + getString(R.string.last_card) + "  ·  " + backend(p.getInt("cardBackend", 0));
      state +=
          "\n" + getString(R.string.last_media) + "  ·  " + backend(p.getInt("mediaBackend", 0));
      state += "\n" + getString(R.string.impulses) + "  ·  " + p.getInt("motionEvents", 0);
      if (!p.getBoolean("open", false)) state += "\n" + getString(R.string.standby);
    }
    state +=
        "\n"
            + getString(p.getInt("failures", 0) >= 3 ? R.string.blocked : R.string.protected_state);
    status.setText(state);
  }

  private String backend(int code) {
    return getString(
        switch (code) {
          case Material.CANVAS -> R.string.backend_canvas;
          case Material.SURFACE -> R.string.backend_surface;
          case Material.TEXTURE -> R.string.backend_texture;
          case Material.ARTWORK -> R.string.backend_art;
          default -> R.string.unseen;
        });
  }

  private void document(boolean export) {
    Intent i =
        new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json");
    if (export) i.putExtra(Intent.EXTRA_TITLE, "LiquidShade.json");
    startActivityForResult(i, export ? 61 : 62);
  }

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (code == 63) {
      Context ctx = getApplicationContext();
      new Thread(
              () -> {
                boolean ok = false;
                try {
                  BackdropPhoto.replace(ctx, uri);
                  ok = true;
                } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                }
                final boolean success = ok;
                runOnUiThread(
                    () -> {
                      if (!isFinishing() && !isDestroyed()) {
                        toast(success ? R.string.success : R.string.failed);
                        render();
                      }
                    });
              },
              "LiquidShade image")
          .start();
      return;
    }
    try {
      if (code == 61) {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
          if (out == null) throw new IOException();
          out.write(cfg.encode().getBytes(StandardCharsets.UTF_8));
        }
      } else if (code == 62) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
          if (in == null) throw new IOException();
          byte[] bytes = new byte[2048];
          int n;
          while ((n = in.read(bytes)) != -1) {
            if (buf.size() + n > 32768) throw new IOException();
            buf.write(bytes, 0, n);
          }
        }
        Config imported = Config.decode(buf.toString("UTF-8"));
        cfg = imported;
        changed();
        render();
      }
      toast(R.string.success);
    } catch (Exception e) {
      toast(code == 62 ? R.string.bad_json : R.string.failed);
    }
  }

  private void restart() {
    new Thread(
            () -> {
              boolean ok = false;
              try {
                java.lang.Process p =
                    new ProcessBuilder("su", "-c", "killall -TERM com.android.systemui").start();
                if (p.waitFor(20, TimeUnit.SECONDS)) ok = p.exitValue() == 0;
                else p.destroy();
              } catch (Exception ignored) {
              }
              final boolean done = ok;
              runOnUiThread(
                  () -> {
                    toast(done ? R.string.success : R.string.failed);
                    main.postDelayed(this::ping, 2000);
                  });
            },
            "LiquidShade restart")
        .start();
  }

  private void toast(int id) {
    Toast.makeText(this, id, Toast.LENGTH_SHORT).show();
  }
}
