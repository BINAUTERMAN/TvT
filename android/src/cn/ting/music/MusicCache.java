package cn.ting.music;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import java.io.*;
import java.util.*;
import org.json.*;

/** 音频由系统下载到应用专属目录，完成校验后才进入离线资料库。 */
final class MusicCache {
  static final String CHANGED = "cn.ting.music.CACHE_CHANGED";
  private static final Object LOCK = new Object();
  private final Context context;
  private final DownloadManager downloads;
  private final android.content.SharedPreferences saved;

  MusicCache(Context context) {
    this.context = context.getApplicationContext();
    downloads = context.getSystemService(DownloadManager.class);
    saved = context.getSharedPreferences("music_cache", Context.MODE_PRIVATE);
  }

  private JSONObject entries() throws Exception {
    return new JSONObject(saved.getString("entries", "{}"));
  }

  private void save(JSONObject entries) throws IOException {
    if (!saved.edit().putString("entries", entries.toString()).commit())
      throw new IOException("无法保存缓存记录");
  }

  private File directory() throws Exception {
    File music = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
    if (music == null) throw new IOException("缓存存储暂不可用");
    File directory = new File(music, "offline");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建音乐缓存目录");
    return directory.getCanonicalFile();
  }

  private File file(JSONObject entry) throws Exception {
    String name = entry.optString("file");
    if (!name.matches("[a-f0-9]{32}-[a-f0-9]{32}\\.audio")) throw new IOException("缓存文件记录无效");
    File root = directory(), result = new File(root, name).getCanonicalFile();
    if (!root.equals(result.getParentFile())) throw new IOException("缓存文件位置无效");
    return result;
  }

  private JSONObject track(JSONObject source) throws Exception {
    JSONObject result = new JSONObject();
    for (String key :
        new String[] {
          "id",
          "hash",
          "title",
          "artist",
          "album",
          "cover",
          "albumId",
          "audioId",
          "duration",
          "artistId",
          "original"
        }) if (source.has(key)) result.put(key, source.get(key));
    return result;
  }

  private JSONObject view(String key, JSONObject entry) throws Exception {
    JSONObject result = new JSONObject(entry.getJSONObject("track").toString());
    return result
        .put("uri", "ting-cache://" + key)
        .put("cached", true)
        .put("cacheQuality", entry.optString("quality", "128"));
  }

  private boolean ready(JSONObject entry) throws Exception {
    if (entry == null || !entry.optString("state").equals("ready")) return false;
    File audio = file(entry);
    return audio.isFile() && audio.length() > 0 && audio.length() == entry.optLong("bytes");
  }

  JSONObject start(KugouApi api, JSONObject source, String quality) throws Exception {
    String key = source.optString("hash").toLowerCase(Locale.ROOT);
    if (!key.matches("[a-f0-9]{32}")) throw new Exception("这首音乐没有可缓存的在线资源");
    synchronized (LOCK) {
      JSONObject existing = entries().optJSONObject(key);
      if (ready(existing)) return new JSONObject().put("state", "ready");
      if (existing != null && existing.optString("state").equals("pending"))
        return new JSONObject().put("state", "pending");
    }
    JSONObject resolved =
        api.bestSongUrl(new JSONObject(source.toString()).put("quality", quality));
    synchronized (LOCK) {
      JSONObject all = entries(), existing = all.optJSONObject(key);
      if (ready(existing)) return new JSONObject().put("state", "ready");
      if (existing != null && existing.optString("state").equals("pending"))
        return new JSONObject().put("state", "pending");
      String name = key + "-" + UUID.randomUUID().toString().replace("-", "") + ".audio";
      directory();
      DownloadManager.Request request =
          new DownloadManager.Request(Uri.parse(resolved.getString("url")))
              .setTitle(source.optString("title", "音乐缓存"))
              .setDescription("TvT · 缓存歌曲，完成后可离线播放")
              .setNotificationVisibility(
                  DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
              .setDestinationInExternalFilesDir(
                  context, Environment.DIRECTORY_MUSIC, "offline/" + name);
      long id = downloads.enqueue(request);
      JSONObject entry =
          new JSONObject()
              .put("downloadId", id)
              .put("file", name)
              .put("state", "pending")
              .put("track", track(source))
              .put("quality", resolved.getString("quality"))
              .put("createdAt", System.currentTimeMillis());
      all.put(key, entry);
      if (!saved.edit().putString("entries", all.toString()).commit()) {
        downloads.remove(id);
        throw new IOException("无法保存缓存任务，请重试");
      }
      if (existing != null) {
        downloads.remove(existing.optLong("downloadId"));
        File previous = file(existing);
        if (previous.exists()) previous.delete();
      }
      return new JSONObject().put("state", "pending");
    }
  }

  private boolean reconcile(JSONObject entry) throws Exception {
    if (!entry.optString("state").equals("pending")) return false;
    try (Cursor cursor =
        downloads.query(new DownloadManager.Query().setFilterById(entry.getLong("downloadId")))) {
      if (cursor == null || !cursor.moveToFirst()) {
        entry.put("state", "failed").put("message", "缓存任务已被系统移除，请重试");
        return true;
      }
      int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      if (status != DownloadManager.STATUS_SUCCESSFUL && status != DownloadManager.STATUS_FAILED)
        return false;
      if (status == DownloadManager.STATUS_FAILED) {
        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
        entry.put("state", "failed").put("message", "缓存失败，请检查网络、存储空间或歌曲权限（" + reason + "）");
      } else {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
          File audio = file(entry);
          long total =
              cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
          if (!audio.isFile() || audio.length() == 0 || (total > 0 && audio.length() != total))
            throw new IOException("缓存文件不完整，请重新缓存");
          metadata.setDataSource(audio.getAbsolutePath());
          String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
          if (duration == null || Long.parseLong(duration) <= 0)
            throw new IOException("缓存音频无法识别，请重试或更换音质");
          entry.getJSONObject("track").put("duration", Long.parseLong(duration) / 1000.0);
          entry.put("bytes", audio.length()).put("state", "ready").remove("message");
        } catch (Exception error) {
          entry
              .put("state", "failed")
              .put(
                  "message",
                  error instanceof IOException ? error.getMessage() : "缓存音频无法识别，请重试或更换音质");
        } finally {
          try {
            metadata.release();
          } catch (Exception ignored) {
          }
        }
      }
      if (entry.optString("state").equals("failed")) downloads.remove(entry.getLong("downloadId"));
      return true;
    }
  }

  boolean completed(long id) throws Exception {
    synchronized (LOCK) {
      JSONObject all = entries();
      for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
        JSONObject entry = all.getJSONObject(keys.next());
        if (entry.optLong("downloadId") == id && reconcile(entry)) {
          save(all);
          context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
          return true;
        }
      }
      return false;
    }
  }

  JSONObject list() throws Exception {
    synchronized (LOCK) {
      JSONObject all = entries();
      JSONArray tracks = new JSONArray(), jobs = new JSONArray();
      long bytes = 0;
      boolean changed = false;
      for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
        String key = keys.next();
        JSONObject entry = all.getJSONObject(key);
        changed |= reconcile(entry);
        if (ready(entry)) {
          tracks.put(view(key, entry));
          bytes += entry.optLong("bytes");
        } else {
          if (entry.optString("state").equals("ready")) {
            entry.put("state", "failed").put("message", "缓存文件已丢失，请重新缓存");
            changed = true;
          }
          jobs.put(
              new JSONObject()
                  .put("hash", key)
                  .put("track", entry.getJSONObject("track"))
                  .put("state", entry.optString("state"))
                  .put("message", entry.optString("message")));
        }
      }
      if (changed) save(all);
      return new JSONObject().put("tracks", tracks).put("jobs", jobs).put("bytes", bytes);
    }
  }

  JSONObject playback(JSONObject source) throws Exception {
    String key = source.optString("hash").toLowerCase(Locale.ROOT);
    boolean required = source.optString("uri").startsWith("ting-cache://");
    if (required) key = Uri.parse(source.getString("uri")).getHost();
    if (required && (key == null || !key.matches("[a-f0-9]{32}"))) throw new IOException("缓存编号无效");
    synchronized (LOCK) {
      JSONObject entry = entries().optJSONObject(key);
      if (ready(entry))
        return new JSONObject()
            .put("uri", Uri.fromFile(file(entry)).toString())
            .put("quality", entry.optString("quality", "128"));
    }
    if (required) throw new IOException("缓存已被清理或文件不完整，请重新缓存");
    return null;
  }

  void remove(String key) throws Exception {
    if (!key.matches("[a-f0-9]{32}")) throw new Exception("缓存编号无效");
    synchronized (LOCK) {
      JSONObject all = entries(), entry = all.optJSONObject(key);
      if (entry == null) return;
      downloads.remove(entry.optLong("downloadId"));
      File audio = file(entry);
      if (audio.exists() && !audio.delete()) throw new IOException("无法清理缓存文件，请稍后重试");
      all.remove(key);
      save(all);
    }
  }
}
