package dev.vadinyt.liquidshade;

import static dev.vadinyt.liquidshade.Option.*;

import org.json.JSONException;
import org.json.JSONObject;

final class Config {
  static final Option[] OPTIONS = Option.values();
  final int[] values = new int[OPTIONS.length];
  int preset = 1;

  Config() {
    for (Option o : OPTIONS) values[o.ordinal()] = o.initial;
  }

  int get(Option o) {
    return values[o.ordinal()];
  }

  boolean on(Option o) {
    return get(o) != 0;
  }

  float unit(Option o) {
    return get(o) / 100f;
  }

  void set(Option o, int v) {
    values[o.ordinal()] = o.maximum < 0 ? v | 0xff000000 : Math.max(0, Math.min(o.maximum, v));
  }

  void select(int index) {
    preset = index;
    if (index == 4) return;
    for (Option o : OPTIONS) {
      if (o.section == R.string.optics
          || o.section == R.string.cards
          || o.section == R.string.media
          || o.section == R.string.shade) set(o, o.initial);
    }
    if (index == 3) set(BASE_COLOR, 0xff263242);
    int[][] table = {
      {61, 10, 10, 12, 8, 17},
      {52, 22, 16, 24, 15, 33},
      {34, 80, 43, 49, 19, 65},
      {49, 34, 62, 55, 57, 42}
    };
    Option[] params = {REFRACTION, BLUR, TINT, CARD_OPACITY, DIM, SHADE_BLUR};
    for (int i = 0; i < params.length; i++) set(params[i], table[index][i]);
  }

  String encode() {
    try {
      JSONObject json = new JSONObject().put("schema", 1).put("preset", preset);
      for (Option o : OPTIONS) json.put(o.name(), get(o));
      return json.toString();
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  static Config decode(String raw) throws JSONException {
    JSONObject json = new JSONObject(raw);
    if (json.getInt("schema") != 1) throw new JSONException("schema");
    Config c = new Config();
    c.preset = json.getInt("preset");
    if (c.preset < 0 || c.preset > 4) throw new JSONException("preset");
    for (Option o : OPTIONS) {
      if (!json.has(o.name())) continue;
      Object item = json.get(o.name());
      if (!(item instanceof Integer)) throw new JSONException(o.name());
      int v = (Integer) item;
      if (o.maximum >= 0 && (v < 0 || v > o.maximum)) throw new JSONException(o.name());
      c.set(o, v);
    }
    return c;
  }
}
