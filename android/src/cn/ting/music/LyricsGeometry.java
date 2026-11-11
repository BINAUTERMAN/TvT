package cn.ting.music;

/** 坐标以可用屏幕区域为基准，避免旋转和改变字号后越界。 */
final class LyricsGeometry {
  static int clamp(int value, int maximum) {
    return Math.max(0, Math.min(Math.max(0, maximum), value));
  }

  static int snap(int value, int maximum, int distance) {
    int bounded = clamp(value, maximum);
    if (bounded <= distance && bounded <= maximum - bounded) return 0;
    if (maximum - bounded <= distance) return Math.max(0, maximum);
    return bounded;
  }

  static float relative(int value, int maximum) {
    return maximum <= 0 ? 0 : (float) clamp(value, maximum) / maximum;
  }

  static int restore(float relative, int maximum) {
    return Math.round(Math.max(0, Math.min(1, relative)) * Math.max(0, maximum));
  }
}
