package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import org.json.*;

public final class HomeLayoutChecks extends Instrumentation {
  private WebView web;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private String javascript(String source) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    String[] result = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, value -> {
      result[0] = value;
      done.countDown();
    }));
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("WebView response timeout");
    return result[0];
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext();
      Activity activity = startActivitySync(new Intent().setClassName(target, "cn.ting.music.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Field field = activity.getClass().getDeclaredField("web");
      field.setAccessible(true);
      web = (WebView) field.get(activity);
      for (int attempt = 0; attempt < 80; attempt++) {
        if (javascript("document.querySelectorAll('.recommend-grid .artwork img').length>0").equals("true")) break;
        SystemClock.sleep(100);
      }
      String value = javascript("JSON.stringify({viewport:innerWidth,"
          + "boxes:['main','.page-stage','.tab-stage','.personal-home','.content-stage','.content-pane','.recommend-grid','.album-item','.album-item .artwork','.album-item img']"
          + ".map(selector=>{const el=document.querySelector(selector);if(!el)return {selector};const r=el.getBoundingClientRect();"
          + "const s=getComputedStyle(el);return {selector,width:r.width,height:r.height,display:s.display,columns:s.gridTemplateColumns,naturalWidth:el.naturalWidth,naturalHeight:el.naturalHeight};}),"
          + "covers:[...document.querySelectorAll('.recommend-grid .artwork')].map(el=>{const r=el.getBoundingClientRect();return {width:r.width,height:r.height};})})");
      JSONObject metrics = new JSONObject((String) new JSONTokener(value).nextValue());
      File output = new File(target.getExternalFilesDir(null), "home-layout");
      output.mkdirs();
      try (FileOutputStream file = new FileOutputStream(new File(output, "metrics.json"))) {
        file.write(metrics.toString(2).getBytes("UTF-8"));
      }
      Bitmap image = getUiAutomation().takeScreenshot();
      try (FileOutputStream file = new FileOutputStream(new File(output, "home.png"))) {
        image.compress(Bitmap.CompressFormat.PNG, 100, file);
      }
      image.recycle();
      JSONArray covers = metrics.getJSONArray("covers");
      if (covers.length() == 0) throw new AssertionError("Home recommendations have not loaded");
      double limit = metrics.getDouble("viewport") / 3;
      for (int index = 0; index < covers.length(); index++) {
        JSONObject cover = covers.getJSONObject(index);
        if (cover.getDouble("width") > limit || Math.abs(cover.getDouble("width") - cover.getDouble("height")) > 1)
          throw new AssertionError("Oversized or non-square home cover: " + cover);
      }
      result.putString("stream", "HOME_LAYOUT_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "HOME_LAYOUT_FAIL: " + error + "\n");
    }
    finish(code, result);
  }
}
