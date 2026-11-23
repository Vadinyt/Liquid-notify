package dev.vadinyt.liquidshade;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.JSONException;

/** The companion owns its prefs. Only the installed SystemUI UID may publish process status. */
public final class Bridge extends ContentProvider {
  static final String AUTH = "dev.vadinyt.liquidshade.bridge";
  static final Uri CONFIG = Uri.parse("content://" + AUTH + "/config");
  static final Uri STATUS = Uri.parse("content://" + AUTH + "/status");
  static final Uri PING = Uri.parse("content://" + AUTH + "/ping");

  static SharedPreferences prefs(Context c) {
    return c.createDeviceProtectedStorageContext()
        .getSharedPreferences("material", Context.MODE_PRIVATE);
  }

  static Config load(Context c) {
    try {
      return Config.decode(prefs(c).getString("config", "{}"));
    } catch (JSONException e) {
      return new Config();
    }
  }

  static void save(Context c, Config cfg) {
    prefs(c).edit().putString("config", cfg.encode()).apply();
    c.getContentResolver().notifyChange(CONFIG, null);
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  private void requireCaller(Context c) {
    int uid = Binder.getCallingUid();
    if (uid == android.os.Process.myUid()) return;
    try {
      if (uid == c.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid) return;
    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
    }
    throw new SecurityException("SystemUI only");
  }

  @Override
  public synchronized Bundle call(String method, String arg, Bundle in) {
    Context c = getContext();
    requireCaller(c);
    SharedPreferences p = prefs(c);
    Bundle out = new Bundle();
    if ("read".equals(method)) {
      out.putString("config", p.getString("config", new Config().encode()));
      out.putBoolean("blocked", p.getInt("failures", 0) >= 3);
    } else if ("failure".equals(method)) {
      int count = Math.min(3, p.getInt("failures", 0) + 1);
      p.edit().putInt("failures", count).commit();
      out.putBoolean("blocked", count >= 3);
      c.getContentResolver().notifyChange(CONFIG, null);
    } else if ("status".equals(method) && in != null) {
      p.edit()
          .putInt("adapters", in.getInt("adapters"))
          .putInt("hooks", in.getInt("hooks"))
          .putInt("surfaces", in.getInt("surfaces"))
          .putBoolean("open", in.getBoolean("open"))
          .putBoolean("lens", in.getBoolean("lens"))
          .putLong("stamp", SystemClock.elapsedRealtime())
          .apply();
      c.getContentResolver().notifyChange(STATUS, null);
    } else throw new IllegalArgumentException("method");
    return out;
  }

  @Override
  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getType(Uri u) {
    return null;
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
