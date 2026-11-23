package dev.vadinyt.liquidshade;

import static org.junit.Assert.*;

import org.junit.Test;

public class LensSpringTest {
  @Test
  public void interruptedPressKeepsPositionAndVelocity() {
    LensSpring s = new LensSpring();
    s.target(1, 4, .52f, .67f);
    s.sample(.055f);
    float position = s.value, velocity = s.velocity;
    assertTrue(position > .25f && position < 1f);
    s.target(0, -4, .52f, .67f);
    s.sample(0);
    assertEquals(position, s.value, .00001f);
    assertEquals(velocity - 4, s.velocity, .0001f);
    s.sample(1.05f);
    assertEquals(0, s.value, .001f);
  }

  @Test
  public void stiffnessAndDampingChangeMotion() {
    LensSpring low = new LensSpring(), high = new LensSpring();
    low.target(1, 0, 0, .5f);
    high.target(1, 0, 1, .5f);
    low.sample(.08f);
    high.sample(.08f);
    assertTrue(high.value - low.value > .35f);
    low.reset();
    high.reset();
    low.target(1, 0, .5f, 0);
    high.target(1, 0, .5f, 1);
    low.sample(.14f);
    high.sample(.14f);
    assertTrue(low.value - high.value > .25f);
    low.finish();
    assertEquals(1, low.value, 0);
    assertEquals(0, low.velocity, 0);
  }
}
