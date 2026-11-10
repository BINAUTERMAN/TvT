package cn.ting.music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;
import org.json.*;

/** KRC 中的译文按原始歌词行对齐，空行不能提前删掉。 */
final class LyricsDocument {
  private static final int[] KEY = {
    64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105
  };

  static String decode(String base64) throws Exception {
    byte[] bytes = Base64.getMimeDecoder().decode(base64);
    if (bytes.length < 4 || bytes[0] != 'k' || bytes[1] != 'r' || bytes[2] != 'c')
      return new String(bytes, StandardCharsets.UTF_8);
    byte[] compressed = Arrays.copyOfRange(bytes, 4, bytes.length);
    for (int index = 0; index < compressed.length; index++)
      compressed[index] ^= KEY[index % KEY.length];
    try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (output.size() + count > 2 * 1024 * 1024) throw new IOException("歌词内容过大");
        output.write(buffer, 0, count);
      }
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  static JSONObject parse(String content) throws Exception {
    JSONArray translation = new JSONArray();
    Matcher language = Pattern.compile("\\[language:([^\\]]+)\\]").matcher(content);
    if (language.find())
      try {
        JSONObject value =
            new JSONObject(
                new String(
                    Base64.getMimeDecoder().decode(language.group(1)), StandardCharsets.UTF_8));
        JSONArray sections = value.optJSONArray("content");
        if (sections != null)
          for (int index = 0; index < sections.length(); index++) {
            JSONObject section = sections.optJSONObject(index);
            if (section != null
                && section.optInt("type", -1) == 1
                && section.optJSONArray("lyricContent") != null) {
              translation = section.getJSONArray("lyricContent");
              break;
            }
          }
      } catch (IllegalArgumentException | JSONException ignored) {
      }
    List<JSONObject> lines = new ArrayList<>();
    boolean translated = false;
    Matcher offsetTag = Pattern.compile("\\[offset:([+-]?\\d+)\\]").matcher(content);
    double offset = offsetTag.find() ? Double.parseDouble(offsetTag.group(1)) / 1000 : 0;
    Pattern krc = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    Pattern lrc = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
    int lyricIndex = 0;
    for (String raw : content.split("\\r?\\n")) {
      Matcher line = krc.matcher(raw);
      if (line.matches()) {
        String text = line.group(3).replaceAll("<\\d+,\\d+,\\d+>", "").trim();
        JSONArray translatedLine = translation.optJSONArray(lyricIndex++);
        String meaning = translatedLine == null ? "" : translatedLine.optString(0, "").trim();
        if (text.isEmpty()) continue;
        JSONObject item =
            new JSONObject()
                .put("time", Long.parseLong(line.group(1)) / 1000.0 + offset)
                .put("text", text);
        long start = Math.round(item.getDouble("time") * 1000);
        item.put("end", start + Long.parseLong(line.group(2)));
        JSONArray words = new JSONArray();
        Matcher word = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)").matcher(line.group(3));
        while (word.find()) {
          String value = word.group(3);
          if (value.trim().isEmpty()) continue;
          long at = start + Long.parseLong(word.group(1));
          words.put(
              new JSONObject()
                  .put("text", value)
                  .put("time", at)
                  .put("end", at + Long.parseLong(word.group(2))));
        }
        if (words.length() > 0) item.put("words", words);
        if (!meaning.isEmpty()) {
          item.put("translation", meaning);
          translated = true;
        }
        lines.add(item);
      } else {
        Matcher stamps = lrc.matcher(raw);
        String text = raw.replaceAll("\\[[^\\]]*\\]", "").trim();
        if (!text.isEmpty())
          while (stamps.find())
            lines.add(
                new JSONObject()
                    .put(
                        "time",
                        Integer.parseInt(stamps.group(1)) * 60
                            + Double.parseDouble(stamps.group(2))
                            + offset)
                    .put("text", text));
      }
    }
    lines.sort(Comparator.comparingDouble(line -> line.optDouble("time")));
    return new JSONObject()
        .put("lines", new JSONArray(lines))
        .put("translationAvailable", translated)
        .put("translationSource", translated ? "酷狗歌词译文" : "");
  }

  static String lrc(JSONObject document) {
    StringBuilder result = new StringBuilder();
    JSONArray lines = document.optJSONArray("lines");
    if (lines == null) return "";
    for (int index = 0; index < lines.length(); index++) {
      JSONObject line = lines.optJSONObject(index);
      if (line == null) continue;
      long time = Math.max(0, Math.round(line.optDouble("time") * 1000));
      result
          .append(
              String.format(
                  Locale.ROOT, "[%02d:%02d.%03d]", time / 60000, time / 1000 % 60, time % 1000))
          .append(line.optString("text"))
          .append('\n');
    }
    return result.toString();
  }
}
