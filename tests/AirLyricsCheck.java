package cn.ting.music;

import org.json.*;

public final class AirLyricsCheck {
  public static void main(String[] args) throws Exception {
    JSONObject line =
        new JSONObject()
            .put("text", "A😀B")
            .put("time", 1)
            .put("end", 4000)
            .put(
                "words",
                new JSONArray()
                    .put(new JSONObject().put("text", "A😀B").put("time", 1000).put("end", 4000)));
    AirLyricsProgress progress = AirLyricsProgress.at(line, 2500);
    if (progress.activeStart != 1 || progress.activeEnd != 3 || progress.completedEnd != 1)
      throw new AssertionError("Karaoke must preserve Unicode surrogate pairs");
    if (AirLyricsProgress.at(line, 4100).completedEnd != 4)
      throw new AssertionError("Completed line remains highlighted");
    if (AirLyricsProgress.nextBoundary(line, 1000) != 2000)
      throw new AssertionError("Karaoke must schedule actual character boundaries");
    if (AirLyricsProgress.coordinate(.5f, 1080, 800) != 140
        || AirLyricsProgress.coordinate(2f, 1080, 2000) != 0)
      throw new AssertionError("Relative window position must stay in screen");
    JSONObject parsed = LyricsDocument.parse("[1000,3000]<0,1000,0>A<1000,1000,0>😀<2000,1000,0>B");
    if (parsed.getJSONArray("lines").getJSONObject(0).getJSONArray("words").length() != 3)
      throw new AssertionError("Real KRC word timing must be preserved");
    System.out.println(
        "PASS AirLyrics Unicode highlight, KRC word timing, event scheduling and window bounds");
  }
}
