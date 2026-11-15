package cn.ting.music;

import java.net.URL;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SurveyPublicCheck {
  public static void main(String[] args) throws Exception {
    String id = UUID.randomUUID().toString();
    JSONObject answer = new JSONObject().put("submissionId", id).put("rating", 5)
        .put("optimization", "").put("favorites", new JSONArray().put("layout"))
        .put("favoriteOther", "").put("improvements", "TvT Java public acceptance check")
        .put("email", "").put("displayId", "tvt-public-check").put("consent", true);
    JSONObject result = SurveyFeedback.post(new URL(args[0]), answer);
    if (!result.getBoolean("accepted") || !id.equals(result.getString("receipt")))
      throw new Exception("公网回执不匹配");
    Thread.sleep(1200);
    SurveyFeedback.post(new URL(args[0]), answer);
    System.out.println("SURVEY_JAVA_PUBLIC_HTTPS_AND_RETRY_PASS ID=" + id);
  }
}
