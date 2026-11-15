package cn.ting.music.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** 发布版验证问卷关闭；启用后才调用配置接口提交专用验收答案。 */
public final class SurveyApiChecks extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext();
      Class<?> type = target.getClassLoader().loadClass("cn.ting.music.SurveyFeedback");
      Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
      constructor.setAccessible(true);
      Object client = constructor.newInstance(target);
      Method status = type.getDeclaredMethod("status"), submit = type.getDeclaredMethod("submit", JSONObject.class);
      status.setAccessible(true);
      submit.setAccessible(true);
      JSONObject current = (JSONObject) status.invoke(client);
      if (!current.getBoolean("available")) {
        Method consume = type.getDeclaredMethod("consume");
        consume.setAccessible(true);
        if (current.getBoolean("eligible") || Boolean.TRUE.equals(consume.invoke(client)))
          throw new Exception("关闭的问卷仍可触发");
        boolean blocked = false;
        try { submit.invoke(client, new JSONObject()); }
        catch (InvocationTargetException expected) {
          blocked = expected.getCause().getMessage().contains("尚未上线");
        }
        if (!blocked) throw new Exception("关闭的问卷仍可提交");
        result.putString("stream", "SURVEY_DISABLED_NATIVE_PASS\n");
        finish(code, result);
        return;
      }
      String id = UUID.randomUUID().toString();
      JSONObject body = new JSONObject().put("submissionId", id).put("rating", 5)
          .put("optimization", "").put("favorites", new JSONArray().put("lightweight"))
          .put("favoriteOther", "").put("improvements", "TvT native public acceptance check")
          .put("email", "").put("displayId", "tvt-device-check").put("consent", true);
      JSONObject reply = (JSONObject) submit.invoke(client, body);
      if (!reply.getBoolean("accepted") || !id.equals(reply.getString("receipt"))) throw new Exception("公网回执无效");
      result.putString("stream", "SURVEY_NATIVE_PUBLIC_HTTPS_PASS ID=" + id + "\n");
    } catch (Throwable failure) {
      code = Activity.RESULT_CANCELED;
      if (failure instanceof InvocationTargetException) failure = failure.getCause();
      result.putString("stream", "SURVEY_NATIVE_PUBLIC_HTTPS_FAIL: " + failure + "\n");
    }
    finish(code, result);
  }
}
