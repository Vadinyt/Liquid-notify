package dev.vadinyt.liquidshade;

/** Exact underdamped step response; position and velocity carry across interrupted gestures. */
final class LensSpring {
  float value, velocity;
  private float start, speed, goal, drag, frequency;

  void target(float target, float impulse, float stiffness, float damping) {
    start = value - target;
    speed = velocity + impulse;
    goal = target;
    float omega = 12 + 22 * stiffness;
    drag = omega * (.30f + .62f * damping);
    frequency = (float) Math.sqrt(omega * omega - drag * drag);
  }

  void sample(float seconds) {
    double envelope = Math.exp(-drag * seconds), phase = frequency * seconds;
    double co = Math.cos(phase), si = Math.sin(phase), b = (speed + drag * start) / frequency;
    double x = start * co + b * si;
    value = goal + (float) (envelope * x);
    velocity = (float) (envelope * ((-start * si + b * co) * frequency - drag * x));
  }

  void finish() {
    value = goal;
    velocity = 0;
  }

  void reset() {
    value = velocity = goal = 0;
  }
}
