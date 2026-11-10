package cn.ting.music;

import org.json.*;

/** 移植自 AirLyrics WordByWordHighlightProgress.kt（MIT，AndSi-327，2026）。 */
final class AirLyricsProgress {
  final int completedEnd, activeStart, activeEnd;
  final float fraction;

  private AirLyricsProgress(int completed, int start, int end, float fraction) {
    completedEnd = completed;
    activeStart = start;
    activeEnd = end;
    this.fraction = fraction;
  }

  static AirLyricsProgress at(JSONObject line, long position) {
    String text = line.optString("text");
    long start = Math.round(line.optDouble("time") * 1000), end = line.optLong("end");
    if (text.trim().isEmpty() || position <= start) return new AirLyricsProgress(0, 0, 0, 0);
    if (end > start && position >= end) return new AirLyricsProgress(text.length(), 0, 0, 0);
    JSONArray words = line.optJSONArray("words");
    int cursor = 0, completed = 0;
    if (words != null)
      for (int index = 0; index < words.length(); index++) {
        JSONObject word = words.optJSONObject(index);
        if (word == null) continue;
        String value = word.optString("text").trim();
        if (value.isEmpty()) continue;
        int at = text.indexOf(value, cursor);
        if (at < 0) at = text.indexOf(value);
        if (at < 0) continue;
        cursor = at + value.length();
        long from = word.optLong("time"), to = word.optLong("end");
        if (position >= to) completed = cursor;
        else if (position >= from) {
          return range(
              text,
              at,
              cursor,
              (float) (position - from) / Math.max(1, to - from),
              Math.max(completed, at));
        } else return new AirLyricsProgress(completed, 0, 0, 0);
      }
    return new AirLyricsProgress(completed, 0, 0, 0);
  }

  private static AirLyricsProgress range(
      String text, int start, int end, float progress, int previous) {
    int count = text.codePointCount(start, end);
    if (count <= 0) return new AirLyricsProgress(previous, 0, 0, 0);
    float continuous = Math.max(0, Math.min(1, progress)) * count;
    int done = Math.min(count, (int) Math.floor(continuous));
    int boundary = text.offsetByCodePoints(start, done);
    if (done == count) return new AirLyricsProgress(Math.max(previous, boundary), 0, 0, 0);
    float fraction = continuous - done;
    fraction = fraction * fraction * (3 - 2 * fraction);
    return new AirLyricsProgress(
        Math.max(previous, boundary), boundary, text.offsetByCodePoints(boundary, 1), fraction);
  }

  static long nextBoundary(JSONObject line, long position) {
    long next = Long.MAX_VALUE;
    JSONArray words = line.optJSONArray("words");
    if (words != null)
      for (int index = 0; index < words.length(); index++) {
        JSONObject word = words.optJSONObject(index);
        if (word == null) continue;
        for (long value : new long[] {word.optLong("time"), word.optLong("end")})
          if (value > position && value < next) next = value;
        String text = word.optString("text").trim();
        int count = text.codePointCount(0, text.length());
        long start = word.optLong("time"), duration = word.optLong("end") - start;
        if (count > 1 && duration > 0)
          for (int letter = 1; letter < count; letter++) {
            long value = start + duration * letter / count;
            if (value > position && value < next) next = value;
          }
      }
    return next;
  }

  static int coordinate(float relative, int available, int size) {
    return Math.round(Math.max(0, Math.min(1, relative)) * Math.max(0, available - size));
  }
}
