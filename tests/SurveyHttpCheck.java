package cn.ting.music;

import java.net.URL;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SurveyHttpCheck {
  public static void main(String[] args) throws Exception {
    JSONObject answer = new JSONObject().put("submissionId", UUID.randomUUID().toString())
        .put("rating", 4).put("optimization", "原生 HTTPS 本地联调")
        .put("favorites", new JSONArray().put("layout")).put("favoriteOther", "")
        .put("improvements", "").put("email", "").put("displayId", "").put("consent", true);
    URL url = new URL(args[0] + "/api/feedback");
    JSONObject first = SurveyFeedback.post(url, answer);
    if (!first.getBoolean("accepted") || !first.getString("receipt").equals(answer.getString("submissionId"))) throw new Exception("回执无效");
    SurveyFeedback.post(url, answer);
    String[][] failures = {{"/denied", "权限、地区或安全验证限制"}, {"/limited", "提交太频繁"}, {"/challenge", "安全验证或无效响应"}, {"/redirect", "HTTP 302"}, {"/wrong-receipt", "尚未确认保存"}, {"/large", "响应过大"}};
    for (String[] item : failures) {
      try { SurveyFeedback.post(new URL(args[0] + item[0]), answer); throw new AssertionError("错误响应未被拒绝 " + item[0]); }
      catch (Exception error) { if (!error.getMessage().contains(item[1])) throw error; }
    }
    System.out.println("SURVEY_NATIVE_HTTPS_LOCAL_PASS");
  }
}
