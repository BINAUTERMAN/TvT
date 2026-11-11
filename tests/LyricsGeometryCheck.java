package cn.ting.music;

public final class LyricsGeometryCheck {
  public static void main(String[] args) {
    for (int maximum : new int[] {-100, 0, 1, 40, 320, 800, 2560}) {
      int limit = Math.max(0, maximum);
      for (int value = -200; value <= limit + 200; value++) {
        for (int distance : new int[] {8, 24, 64, 192}) {
          int result = LyricsGeometry.snap(value, maximum, distance);
          check(result >= 0 && result <= limit, "吸附必须留在屏幕内");
          check(LyricsGeometry.snap(result, maximum, distance) == result, "吸附必须稳定");
          int bounded = LyricsGeometry.clamp(value, maximum);
          if (bounded > distance && limit - bounded > distance)
            check(result == bounded, "远离边缘时不得改变位置");
        }
        check(
            LyricsGeometry.restore(LyricsGeometry.relative(value, maximum), maximum)
                == LyricsGeometry.clamp(value, maximum),
            "位置保存与恢复必须一致");
      }
    }
    check(LyricsGeometry.snap(23, 800, 24) == 0, "左边与上边吸附");
    check(LyricsGeometry.snap(777, 800, 24) == 800, "右边与下边吸附");
    check(LyricsGeometry.restore(1, 240) == 240, "窗口变高后仍贴底");
    check(LyricsGeometry.restore(1, -30) == 0, "窗口超出可用区域时不得产生负坐标");
    System.out.println(
        "PASS desktop lyrics bounds, four-edge snapping, position restore and resizing");
  }

  private static void check(boolean result, String message) {
    if (!result) throw new AssertionError(message);
  }
}
