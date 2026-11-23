package dev.vadinyt.liquidshade;

import static org.junit.Assert.*;

import org.junit.Test;

public class RowPoseTest {
  @Test
  public void originAndAmplitudeAreIndependentOfScreenTranslation() {
    RowPose p = new RowPose();
    p.set(10, 20, 900, 180, 3, 1, .8f, 1, 0);
    assertTrue(p.px > 600 && p.px < 650);
    assertEquals(110, p.py, .001);
    assertTrue(p.sx < .96 && p.sy < .95);
    p.set(10, 20, 900, 180, 3, 1, 0, 1, 0);
    assertEquals(1, p.sx, 0);
    assertEquals(1, p.sy, 0);
    assertEquals(0, p.dy, 0);
  }

  @Test
  public void olderJsonReceivesNewMotionControl() throws Exception {
    Config c = Config.decode("{\"schema\":1,\"preset\":4,\"SPEED\":73,\"MOTION_ON\":0}");
    assertEquals(80, c.get(Option.MOTION_AMOUNT));
    assertFalse(c.on(Option.MOTION_ON));
    assertEquals(73, c.get(Option.SPEED));
  }
}
