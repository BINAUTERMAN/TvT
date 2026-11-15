package cn.ting.music;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class SurveyFeedback {
  private final Context context;

  SurveyFeedback(Context context) { this.context = context; }

  static boolean isEnabled(Context context) {
    try (InputStream input = context.getAssets().open("survey.json")) {
      return new JSONObject(read(input, 4096)).optBoolean("enabled", false);
    } catch (Exception ignored) { return false; }
  }

  private String endpoint() throws Exception {
    if (!isEnabled(context)) return "";
    try (InputStream input = context.getAssets().open("survey.json")) {
      String value = new JSONObject(read(input, 4096)).optString("endpoint");
      if (value.isEmpty()) return "";
      URL url = new URL(value);
      if (!url.getProtocol().equals("https") || url.getHost().isEmpty()
          || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
          || !url.getPath().equals("/api/feedback")) throw new Exception("问卷服务配置无效");
      return value;
    }
  }

  JSONObject status() throws Exception {
    boolean available = !endpoint().isEmpty();
    JSONObject value = new StarPrompt(context).surveyStatus();
    return value.put("available", available).put("eligible", available && value.getBoolean("eligible"));
  }

  boolean consume() throws Exception {
    return !endpoint().isEmpty() && new StarPrompt(context).consumeSurvey();
  }

  JSONObject submit(JSONObject data) throws Exception {
    String target = endpoint();
    if (target.isEmpty()) throw new Exception("问卷服务尚未上线，请稍后再来 (´･ω･`)");
    if (data.toString().getBytes(StandardCharsets.UTF_8).length > 8192)
      throw new Exception("填写内容过长");
    return post(new URL(target), data);
  }

  static JSONObject post(URL target, JSONObject data) throws Exception {
    HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();
    try {
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(15000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      connection.setDoOutput(true);
      byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream output = connection.getOutputStream()) { output.write(body); }
      int code = connection.getResponseCode();
      if (code == 403 || code == 401) throw new Exception("问卷被权限、地区或安全验证限制，请稍后重试");
      if (code == 429) throw new Exception("提交太频繁，请稍后再试");
      if (code != 200 && code != 201) throw new Exception("问卷提交失败（HTTP " + code + "），请稍后重试");
      String source;
      try (InputStream input = connection.getInputStream()) { source = read(input, 4096); }
      JSONObject result;
      try { result = new JSONObject(source); }
      catch (Exception invalid) { throw new Exception("问卷服务返回了安全验证或无效响应，请稍后重试"); }
      // 只有服务端确认保存，才显示感谢页。
      String id = data.optString("submissionId");
      if (id.isEmpty() || !result.optBoolean("accepted") || !id.equals(result.optString("receipt")))
        throw new Exception("服务端尚未确认保存，请稍后重试");
      return result;
    } finally { connection.disconnect(); }
  }

  private static String read(InputStream input, int limit) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int size;
    while ((size = input.read(buffer)) != -1) {
      if (output.size() + size > limit) throw new Exception("问卷服务响应过大");
      output.write(buffer, 0, size);
    }
    return new String(output.toByteArray(), StandardCharsets.UTF_8);
  }
}
