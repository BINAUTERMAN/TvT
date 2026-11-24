package cn.ting.music;

/** 正常使用播放器位置；系统位置异常回退后，以事件驱动的播放时钟续接。 */
final class PlaybackClock {
  private long anchor, anchoredAt, reported, lastNative;
  private float speed = 1f;
  private boolean running, discontinuous;

  void reset(long position, long now) {
    anchor = reported = lastNative = Math.max(0, position);
    anchoredAt = now;
    running = discontinuous = false;
  }

  void start(long now) {
    if (running) return;
    anchor = reported;
    anchoredAt = now;
    running = true;
  }

  long position(long nativePosition, long now) {
    if (!running) return reported;
    long expected = anchor + Math.round(Math.max(0, now - anchoredAt) * (double) speed);
    if (nativePosition + 250 < lastNative) discontinuous = true;
    lastNative = nativePosition;
    reported = Math.max(reported, discontinuous ? expected : nativePosition);
    return reported;
  }

  void pause(long nativePosition, long now) {
    anchor = position(nativePosition, now);
    anchoredAt = now;
    running = false;
  }

  void speed(float value, long now) {
    anchor = reported;
    anchoredAt = now;
    speed = value;
  }
}
