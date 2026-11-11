package cn.ting.music;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅使用真实逐字时间；缺失时保留整句高亮。 */
final class LyricsTimeline {
  static final class Word {
    final int start, end;
    final long from, to;

    Word(int start, int end, long from, long to) {
      this.start = start;
      this.end = end;
      this.from = from;
      this.to = to;
    }

    float fraction(long position) {
      return unit((float) (position - from) / (to - from));
    }
  }

  final String text;
  final long start, end;
  final Word[] words;

  LyricsTimeline(JSONObject line) {
    text = line.optString("text");
    start = Math.round(line.optDouble("time") * 1000);
    long reportedEnd = line.optLong("end", Long.MAX_VALUE);
    end = reportedEnd > start ? reportedEnd : Long.MAX_VALUE;
    List<Word> result = new ArrayList<>();
    JSONArray source = line.optJSONArray("words");
    int cursor = 0;
    long previous = Long.MIN_VALUE;
    if (source != null)
      for (int i = 0; i < source.length(); i++) {
        JSONObject word = source.optJSONObject(i);
        if (word == null) continue;
        String value = word.optString("text").trim();
        long from = word.optLong("time"), to = word.optLong("end");
        if (value.isEmpty() || to <= from || from < previous) continue;
        int at = text.indexOf(value, cursor);
        if (at < 0) continue;
        cursor = at + value.length();
        result.add(new Word(at, cursor, from, to));
        previous = to;
      }
    words = result.toArray(new Word[0]);
  }

  boolean moving(long position) {
    for (Word word : words) if (position >= word.from && position < word.to) return true;
    return false;
  }

  long nextChange(long position) {
    long next = end > position ? end : Long.MAX_VALUE;
    for (Word word : words) {
      if (word.from > position) next = Math.min(next, word.from);
      if (word.to > position) next = Math.min(next, word.to);
    }
    return next;
  }

  static float unit(float value) {
    return Math.max(0, Math.min(1, value));
  }

  static float scroll(float textWidth, float viewport, long position, long from, long duration) {
    return Math.max(0, textWidth - viewport)
        * unit((float) (position - from) / Math.max(1, duration));
  }
}
