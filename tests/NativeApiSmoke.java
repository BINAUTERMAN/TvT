package cn.ting.music;

import java.net.*;
import java.nio.file.*;
import java.util.UUID;
import org.json.*;

public final class NativeApiSmoke {
  public static void main(String[] arguments) throws Exception {
    KugouApi api = new KugouApi(UUID.randomUUID().toString().replace("-", ""));
    JSONObject result = api.search("纯音乐", 1);
    JSONArray songs = result.getJSONObject("data").getJSONArray("lists");
    if (songs.length() == 0) throw new AssertionError("搜索没有返回歌曲");
    Files.createDirectories(Paths.get("tests/fixtures"));
    Files.write(Paths.get("tests/fixtures/search.json"), result.toString().getBytes("UTF-8"));
    System.out.println("PASS native search: " + songs.length());
    boolean playable = false;
    for (int index = 0; index < Math.min(8, songs.length()); index++) {
      JSONObject raw = songs.getJSONObject(index);
      JSONObject track =
          new JSONObject()
              .put("hash", raw.getString("FileHash"))
              .put("title", raw.optString("SongName"))
              .put("albumId", raw.optString("AlbumID", "0"))
              .put("audioId", raw.optString("MixSongID", "0"));
      try {
        String url = api.songUrl(track);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Range", "bytes=0-255");
        int status = connection.getResponseCode();
        try (java.io.InputStream stream = connection.getInputStream()) {
          if ((status != 200 && status != 206) || stream.read() < 0)
            throw new AssertionError("音频数据不可读");
        } finally {
          connection.disconnect();
        }
        System.out.println("PASS native song URL and audio range: " + status);
        playable = true;
      } catch (Exception exception) {
        System.out.println("Track unavailable: " + exception.getMessage());
      }
      try {
        String lyric = api.lyrics(track);
        if (lyric.contains("["))
          System.out.println("PASS native lyrics: " + lyric.length() + " chars");
      } catch (Exception exception) {
        System.out.println("Lyrics unavailable: " + exception.getClass().getSimpleName());
      }
      if (playable) break;
    }
    JSONObject qr = api.qrKey();
    JSONObject qrData = qr.optJSONObject("data");
    if (qrData != null && !qrData.optString("qrcode").isEmpty())
      System.out.println("PASS native login QR generation");
    else
      System.out.println(
          "QR response keys: "
              + qr.keySet()
              + "; data keys: "
              + (qrData == null ? "none" : qrData.keySet()));
    if (!playable) throw new AssertionError("未找到可播放测试曲目");
  }
}
