package cn.ting.music.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 在真实 WebView 中检查发布版问卷隐藏，不发送答案或消费邀请。 */
public final class SurveyUiChecks extends Instrumentation {
  private WebView web;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  private String js(String source) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    String[] result = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, value -> { result[0] = value; done.countDown(); }));
    if (!done.await(10, TimeUnit.SECONDS)) throw new Exception("WebView 超时");
    return result[0];
  }

  private void waitFor(String condition) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (js(condition).equals("true")) return;
      SystemClock.sleep(100);
    }
    throw new Exception("界面条件未满足：" + condition);
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext();
      Activity activity = startActivitySync(new Intent().setClassName(target, "cn.ting.music.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Field field = activity.getClass().getDeclaredField("web");
      field.setAccessible(true);
      web = (WebView) field.get(activity);
      boolean shown = target.getSharedPreferences("star_prompt", Context.MODE_PRIVATE).getBoolean("surveyShown", false);
      waitFor("!!document.querySelector('.app-shell')");
      js("document.querySelector('.close-sheet')?.click()");
      js("window.dispatchEvent(new Event('ting:star-ready'));window.dispatchEvent(new Event('ting:resume'))");
      waitFor("!document.querySelector('.survey-preview-button,.survey-invitation,.survey-page')");
      js("document.querySelector('[aria-label=\"账号与设置\"]').click()");
      waitFor("!!document.querySelector('.sheet-panel')");
      js("[...document.querySelectorAll('button')].find(el=>el.textContent.trim()==='偏好设置')?.click()");
      waitFor("[...document.querySelectorAll('button')].some(el=>el.textContent.trim()==='应用更新')");
      if (!js("![...document.querySelectorAll('button')].some(el=>/问卷/.test(el.textContent))").equals("true")) throw new Exception("仍有问卷入口");
      if (target.getSharedPreferences("star_prompt", Context.MODE_PRIVATE).getBoolean("surveyShown", false) != shown) throw new Exception("预览改写了邀请记录");
      result.putString("stream", "SURVEY_REAL_WEBVIEW_HIDDEN_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "SURVEY_REAL_WEBVIEW_HIDDEN_FAIL: " + error + "\n");
    }
    finish(code, result);
  }
}
