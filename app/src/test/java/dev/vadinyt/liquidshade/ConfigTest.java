package dev.vadinyt.liquidshade;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class ConfigTest {
  @Test
  public void everyOptionSurvivesExportImport() throws Exception {
    Config config = new Config();
    config.select(3);
    config.preset = 4;
    for (Option option : Config.OPTIONS) {
      int value = option.maximum < 0 ? 0xff286ba2 : option.maximum;
      config.set(option, value);
    }
    Config copy = Config.decode(config.encode());
    assertArrayEquals(config.values, copy.values);
    assertEquals(4, copy.preset);
  }

  @Test
  public void presetsKeepUserGlobalPreferencesAndDarkTint() {
    Config c = new Config();
    c.set(Option.ENABLED, 0);
    c.set(Option.PERFORMANCE, 2);
    c.select(3);
    assertFalse(c.on(Option.ENABLED));
    assertEquals(2, c.get(Option.PERFORMANCE));
    assertEquals(0xff263242, c.get(Option.BASE_COLOR));
    c.select(4);
    assertEquals(0xff263242, c.get(Option.BASE_COLOR));
  }

  @Test
  public void badImportIsRejected() throws Exception {
    JSONObject data = new JSONObject(new Config().encode()).put("BEVEL", 101);
    assertThrows(JSONException.class, () -> Config.decode(data.toString()));
    data.put("BEVEL", "50");
    assertThrows(JSONException.class, () -> Config.decode(data.toString()));
    data.put("BEVEL", 50).put("schema", 2);
    assertThrows(JSONException.class, () -> Config.decode(data.toString()));
  }
}
