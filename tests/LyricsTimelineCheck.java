package cn.ting.music;

import org.json.JSONArray;
import org.json.JSONObject;

public final class LyricsTimelineCheck {
  public static void main(String[] args) throws Exception {
    JSONObject line =
        new JSONObject()
            .put("text", "A😀A")
            .put("time", 1)
            .put("end", 5000)
            .put(
                "words",
                new JSONArray()
                    .put(new JSONObject().put("text", "A").put("time", 1000).put("end", 1800))
                    .put(new JSONObject().put("text", "😀").put("time", 2000).put("end", 3000))
                    .put(new JSONObject().put("text", "A").put("time", 3500).put("end", 4500)));
    LyricsTimeline timeline = new LyricsTimeline(line);
    check(timeline.words.length == 3, "真实逐字时间保留");
    check(timeline.words[1].start == 1 && timeline.words[1].end == 3, "表情不能被截断");
    check(timeline.words[2].start == 3, "重复字必须按顺序定位");
    check(Math.abs(timeline.words[0].fraction(1200) - .25f) < .001f, "扫色必须在字内连续推进");
    check(timeline.words[0].fraction(900) == 0 && timeline.words[0].fraction(1900) == 1, "进度不得越界");
    check(!timeline.moving(1900) && timeline.nextChange(1900) == 2000, "字间停顿不持续请求帧");
    check(!timeline.moving(5500) && timeline.nextChange(5500) == Long.MAX_VALUE, "结束后没有帧任务");
    check(
        timeline.words[2].fraction(4000) == .5f && timeline.words[0].fraction(1400) == .5f,
        "拖动进度后无前一字的状态残留");
    check(LyricsTimeline.scroll(900, 600, 900, 1000, 300) == 0, "长句滚动前保持位置");
    check(LyricsTimeline.scroll(900, 600, 1150, 1000, 300) == 150, "长句按歌曲时间线移动");
    check(LyricsTimeline.scroll(900, 600, 2000, 1000, 300) == 300, "句尾不能滚出可见范围");
    check(LyricsTimeline.scroll(300, 600, 2000, 1000, 300) == 0, "短句不滚动");
    LyricsTimeline plain =
        new LyricsTimeline(new JSONObject().put("text", "没有逐字时间").put("time", 1));
    check(plain.words.length == 0 && !plain.moving(1500), "不伪造逐字时间");
    System.out.println(
        "PASS continuous sweep, Unicode, repeated words, seek, word gaps and song-timed scrolling");
  }

  private static void check(boolean passed, String message) {
    if (!passed) throw new AssertionError(message);
  }
}
