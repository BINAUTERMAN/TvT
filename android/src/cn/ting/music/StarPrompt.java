package cn.ting.music;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 仅保存本机计数和展示标记；同一次播放的恢复不重复计数。 */
final class StarPrompt {
  static final String READY = "cn.ting.music.STAR_READY";
  private static final Object LOCK = new Object();
  private final Context context;
  private final SharedPreferences saved;

  StarPrompt(Context context) {
    this.context = context.getApplicationContext();
    saved = context.getSharedPreferences("star_prompt", Context.MODE_PRIVATE);
  }

  void started(String session) {
    synchronized (LOCK) {
      if (session.isEmpty()
          || saved.getBoolean("shown", false)
          || session.equals(saved.getString("lastSession", ""))) return;
      int previous = saved.getInt("plays", 0), count = Math.min(5, previous + 1);
      boolean stored =
          saved.edit().putInt("plays", count).putString("lastSession", session).commit();
      if (stored && previous < 5 && count == 5)
        context.sendBroadcast(new Intent(READY).setPackage(context.getPackageName()));
    }
  }

  String repositoryUrl() throws Exception {
    try (InputStream input = context.getAssets().open("update.json")) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int length;
      while ((length = input.read(buffer)) != -1) {
        if (output.size() + length > 8192) throw new Exception("项目配置无效");
        output.write(buffer, 0, length);
      }
      JSONObject config = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
      String repository = config.optString("projectRepository", config.optString("repository"));
      if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new Exception("尚未配置项目仓库");
      return "https://github.com/" + repository;
    }
  }

  JSONObject status() throws Exception {
    synchronized (LOCK) {
      return new JSONObject()
          .put("eligible", saved.getInt("plays", 0) >= 5 && !saved.getBoolean("shown", false));
    }
  }

  boolean consume() throws Exception {
    synchronized (LOCK) {
      if (!status().optBoolean("eligible")) return false;
      repositoryUrl();
      return saved.edit().putBoolean("shown", true).remove("lastSession").commit();
    }
  }
}
