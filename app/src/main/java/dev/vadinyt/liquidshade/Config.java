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
    set(BASE_COLOR, index == 3 ? 0xff263242 : BASE_COLOR.initial);
    Option[] params = {
      REFRACTION,
      BLUR,
      COMPRESSION,
      DEPTH,
      BEVEL,
      DISPERSION,
      REFLECTION,
      RIM,
      TINT,
      CARD_OPACITY,
      DIM,
      SHADE_BLUR,
      MEDIA_BLUR,
      MEDIA_REFRACTION,
      MEDIA_OPACITY,
      MEDIA_RIM
    };
    int[][] values = {
      {79, 8, 68, 70, 49, 14, 53, 72, 5, 9, 6, 12, 8, 67, 10, 64},
      {74, 29, 62, 65, 53, 10, 54, 69, 9, 16, 12, 38, 16, 43, 22, 49},
      {42, 78, 43, 63, 57, 8, 64, 69, 35, 39, 10, 53, 65, 44, 31, 65},
      {62, 28, 65, 69, 50, 10, 54, 67, 70, 65, 34, 28, 31, 52, 52, 68}
    };
    for (int i = 0; i < params.length; i++) set(params[i], values[index][i]);
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
