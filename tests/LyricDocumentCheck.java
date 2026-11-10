package cn.ting.music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import org.json.*;

public final class LyricDocumentCheck {
  public static void main(String[] args) throws Exception {
    String language =
        new JSONObject()
            .put(
                "content",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("type", 1)
                            .put(
                                "lyricContent",
                                new JSONArray()
                                    .put(new JSONArray().put("第一句译文"))
                                    .put(new JSONArray().put(""))
                                    .put(new JSONArray().put("第三句译文")))))
            .toString();
    String krc =
        "[offset:100]\n[language:"
            + Base64.getEncoder().encodeToString(language.getBytes(StandardCharsets.UTF_8))
            + "]\n"
            + "[3000,1000]<0,1000,0>First sentence\n"
            + "[2000,0]\n"
            + "[1000,1000]<0,1000,0>Third sentence";
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) {
      output.write(krc.getBytes(StandardCharsets.UTF_8));
    }
    byte[] zipped = compressed.toByteArray(),
        wire = new byte[zipped.length + 4],
        key = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105};
    System.arraycopy(new byte[] {'k', 'r', 'c', '1'}, 0, wire, 0, 4);
    for (int index = 0; index < zipped.length; index++)
      wire[index + 4] = (byte) (zipped[index] ^ key[index % 16]);
    JSONObject parsed =
        LyricsDocument.parse(LyricsDocument.decode(Base64.getEncoder().encodeToString(wire)));
    JSONArray lines = parsed.getJSONArray("lines");
    if (lines.length() != 2
        || !parsed.getBoolean("translationAvailable")
        || !lines.getJSONObject(0).getString("translation").equals("第三句译文")
        || Math.abs(lines.getJSONObject(0).getDouble("time") - 1.1) > .001)
      throw new AssertionError("KRC translation alignment failed");
    if (!LyricsDocument.lrc(parsed).contains("[00:01.100]Third sentence"))
      throw new AssertionError("LRC fallback conversion failed");
    if (LyricsDocument.parse("[00:01.00]An original sentence").getBoolean("translationAvailable"))
      throw new AssertionError("Missing translation must not be fabricated");
    System.out.println(
        "PASS KRC decoding, translation alignment, offset and untranslated fallback");
    KugouApi api = new KugouApi(UUID.randomUUID().toString().replace("-", ""));
    int translated = 0;
    for (String keyword : new String[] {"Shape of You", "Love Story Taylor Swift", "Lemon 米津玄師"}) {
      JSONArray results = api.search(keyword, 1).getJSONObject("data").getJSONArray("lists");
      if (results.length() == 0) continue;
      JSONObject song = results.getJSONObject(0);
      JSONObject doc =
          api.lyricDocument(
              new JSONObject()
                  .put("hash", song.optString("FileHash"))
                  .put("title", song.optString("SongName")));
      int count = doc.getJSONArray("lines").length();
      boolean has = doc.getBoolean("translationAvailable");
      int chinese = 0;
      JSONArray translatedLines = doc.getJSONArray("lines");
      for (int index = 0; index < translatedLines.length(); index++)
        if (translatedLines
            .getJSONObject(index)
            .optString("translation")
            .matches(".*[\\u4e00-\\u9fff].*")) chinese++;
      if (has && chinese > 0) translated++;
      System.out.println(
          "Foreign lyric sample lines="
              + count
              + " translation="
              + has
              + " Chinese translated lines="
              + chinese);
    }
    if (translated == 0) throw new AssertionError("No translated sample was confirmed");
    System.out.println("LIVE_PLATFORM_TRANSLATION_PASS");
  }
}
