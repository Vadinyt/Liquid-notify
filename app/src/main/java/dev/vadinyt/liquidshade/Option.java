package dev.vadinyt.liquidshade;

enum Option {
  ENABLED(R.string.global, R.string.enabled, 1, 1),
  AUTO_MODE(R.string.global, R.string.auto_mode, 1, 1),
  PERFORMANCE(R.string.global, R.string.performance, 1, 2),
  REFRACTION(R.string.optics, R.string.refraction, 75, 100),
  BLUR(R.string.optics, R.string.blur, 44, 100),
  COMPRESSION(R.string.optics, R.string.compression, 67, 100),
  DEPTH(R.string.optics, R.string.depth, 64, 100),
  BEVEL(R.string.optics, R.string.bevel, 39, 100),
  DISPERSION(R.string.optics, R.string.dispersion, 8, 100),
  REFLECTION(R.string.optics, R.string.reflection, 48, 100),
  RIM(R.string.optics, R.string.rim, 64, 100),
  BASE_COLOR(R.string.optics, R.string.base_color, 0xffe4f2f7, -1),
  TINT(R.string.optics, R.string.tint, 5, 100),
  SATURATION(R.string.optics, R.string.saturation, 50, 100),
  VIBRANCY(R.string.optics, R.string.vibrancy, 34, 100),
  CARDS_ON(R.string.cards, R.string.cards_on, 1, 1),
  CARD_OPACITY(R.string.cards, R.string.card_opacity, 13, 100),
  CARD_RADIUS(R.string.cards, R.string.card_radius, 54, 100),
  BORDER(R.string.cards, R.string.border, 33, 100),
  SPACING(R.string.cards, R.string.spacing, 18, 100),
  HIGHLIGHT(R.string.cards, R.string.highlight, 0xffE8F5FF, -1),
  CONTRAST(R.string.cards, R.string.contrast, 1, 1),
  GROUPS(R.string.cards, R.string.groups, 1, 2),
  PRESS(R.string.cards, R.string.press, 1, 1),
  MEDIA_ON(R.string.media, R.string.media_on, 1, 1),
  MEDIA_BLUR(R.string.media, R.string.media_blur, 16, 100),
  MEDIA_REFRACTION(R.string.media, R.string.media_refraction, 43, 100),
  ART_TINT(R.string.media, R.string.art_tint, 1, 1),
  MEDIA_OPACITY(R.string.media, R.string.media_opacity, 22, 100),
  MEDIA_RADIUS(R.string.media, R.string.media_radius, 55, 100),
  PROGRESS(R.string.media, R.string.progress, 0, 2),
  MEDIA_RIM(R.string.media, R.string.media_rim, 49, 100),
  SHADE_ON(R.string.shade, R.string.shade_on, 1, 1),
  PHOTO_MODE(R.string.shade, R.string.photo_mode, 0, 1),
  SHADE_BLUR(R.string.shade, R.string.shade_blur, 39, 100),
  DIM(R.string.shade, R.string.dim, 12, 100),
  SHADE_COLOR(R.string.shade, R.string.shade_color, 0xff7391B4, -1),
  SHADE_TINT(R.string.shade, R.string.shade_tint, 7, 100),
  GRADIENT(R.string.shade, R.string.gradient, 19, 100),
  FOOTER(R.string.shade, R.string.footer, 1, 1),
  MOTION_ON(R.string.motion, R.string.motion_on, 1, 1),
  MOTION_AMOUNT(R.string.motion, R.string.motion_amount, 80, 100),
  STIFFNESS(R.string.motion, R.string.stiffness, 52, 100),
  DAMPING(R.string.motion, R.string.damping, 67, 100),
  SPEED(R.string.motion, R.string.speed, 48, 100),
  REDUCE(R.string.motion, R.string.reduce, 1, 1),
  LOGGING(R.string.advanced, R.string.logging, 0, 1);
  final int section, label, initial, maximum;

  Option(int section, int label, int initial, int maximum) {
    this.section = section;
    this.label = label;
    this.initial = initial;
    this.maximum = maximum;
  }
}
