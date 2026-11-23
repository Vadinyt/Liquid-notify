package dev.vadinyt.liquidshade;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class ConfigTest {
  @Test
  public void upgradingOrRestoringAnOldFileUsesTheNativeShade() throws Exception {
    Config old = Config.decode("{\"schema\":1,\"preset\":3,\"SHADE_ON\":1}");
    assertFalse(old.usesPhoto(true));
    old.set(Option.PHOTO_MODE, 1);
    assertFalse(old.usesPhoto(false));
    assertTrue(Config.decode(old.encode()).usesPhoto(true));
    assertFalse(new Config().usesPhoto(true));
  }

  private static final class Row {
    public boolean mOnKeyguard;
  }

  @Test
  public void lockCheckUsesTheRegisteredRowAndFailsClosedForOtherViews() throws Exception {
    Row row = new Row();
    java.lang.reflect.Field flag = Row.class.getDeclaredField("mOnKeyguard");
    assertTrue(ShadeAccess.unlockedRow(flag, row));
    assertFalse(ShadeAccess.unlockedRow(flag, new Object()));
    row.mOnKeyguard = true;
    assertFalse(ShadeAccess.unlockedRow(flag, row));
    assertFalse(ShadeAccess.unlockedRow(null, row));
  }

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
  public void materialPresetsPreserveSwitchesAndGroupGeometry() {
    Config c = new Config();
    c.set(Option.SHADE_ON, 0);
    c.set(Option.PRESS, 0);
    c.set(Option.GROUPS, 2);
    c.select(0);
    int clearBlur = c.get(Option.MEDIA_BLUR);
    c.select(2);
    assertTrue(c.get(Option.MEDIA_BLUR) > clearBlur);
    assertFalse(c.on(Option.SHADE_ON));
    assertFalse(c.on(Option.PRESS));
    assertEquals(2, c.get(Option.GROUPS));
    c.select(1);
    assertEquals(Option.REFRACTION.initial, c.get(Option.REFRACTION));
  }

  @Test
  public void signatureTracksChangesAndSurvivesImport() throws Exception {
    Config c = new Config();
    int initial = c.signature();
    c.set(Option.BLUR, 90);
    assertNotEquals(initial, c.signature());
    assertEquals(c.signature(), Config.decode(c.encode()).signature());
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
