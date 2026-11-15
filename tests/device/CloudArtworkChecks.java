package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.util.concurrent.*;

/** 只读取歌单，检查冷启动封面以及空歌单详情刷新，不修改云端内容。 */
public final class CloudArtworkChecks extends Instrumentation {
  private WebView web;
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private String js(String source) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    String[] value = new String[1];
    runOnMainSync(() -> web.evaluateJavascript(source, result -> { value[0] = result; done.countDown(); }));
    if (!done.await(10, TimeUnit.SECONDS)) throw new Exception("WebView timeout");
    return value[0];
  }
  private void waitFor(String condition) throws Exception {
    for (int attempt = 0; attempt < 300; attempt++) {
      if (js(condition).equals("true")) return;
      SystemClock.sleep(100);
    }
    throw new Exception("Artwork condition timed out");
  }
  @Override public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext();
      Activity activity = startActivitySync(new Intent().setClassName(target, "cn.ting.music.MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Field field = activity.getClass().getDeclaredField("web");
      field.setAccessible(true);
      web = (WebView) field.get(activity);
      waitFor("!!document.querySelector('.bottom-nav')");
      js("document.querySelector('.close-sheet')?.click();[...document.querySelectorAll('.bottom-nav button')].find(b=>b.textContent.trim()==='资料库').click()");
      waitFor("!!document.querySelector('.cloud-playlists .playlist-card')");
      js("document.querySelector('.cloud-playlists').scrollIntoView({block:'start'})");
      waitFor("(()=>{const cards=[...document.querySelectorAll('.cloud-playlists .playlist-card')].filter(c=>!c.querySelector('small').textContent.includes('· 0 首')&&c.getBoundingClientRect().top<innerHeight);return cards.every(c=>{const i=c.querySelector('img');return i&&i.complete&&i.naturalWidth>0&&getComputedStyle(i).opacity==='1'})})()");
      if (!js("!document.querySelector('.cloud-playlist-detail')").equals("true"))
        throw new Exception("Unexpected playlist detail");
      boolean empty = js("window.__coverProbe=[...document.querySelectorAll('.cloud-playlists .playlist-card')].find(c=>c.querySelector('small').textContent.includes('· 0 首'));!!window.__coverProbe").equals("true");
      if (empty) {
        js("window.__coverProbe.scrollIntoView({block:'center'})");
        waitFor("(()=>{const i=window.__coverProbe.querySelector('img');return i&&i.complete&&i.naturalWidth>0&&getComputedStyle(i).opacity==='1'})()");
        js("window.__coverProbeSource=window.__coverProbe.querySelector('img').getAttribute('src');window.__coverProbe.click()");
        waitFor("!!document.querySelector('.cloud-playlist-detail')&&!document.querySelector('.cloud-playlist-detail p.loading')");
        String visible = "(()=>{const i=document.querySelector('.cloud-playlist-detail .entity-heading img');return i&&i.complete&&i.naturalWidth>0&&getComputedStyle(i).opacity==='1'&&i.getAttribute('src')===window.__coverProbeSource})()";
        waitFor(visible);
        js("document.querySelector('[aria-label=\"刷新歌单歌曲\"]').click()");
        waitFor("!document.querySelector('.cloud-playlist-detail p.loading')&&!document.querySelector('[aria-label=\"刷新歌单歌曲\"]').disabled");
        waitFor(visible);
        boolean editable = js("!![...document.querySelectorAll('.cloud-playlist-detail button')].find(b=>b.textContent.trim()==='编辑歌单')").equals("true");
        if (editable) {
          js("[...document.querySelectorAll('.cloud-playlist-detail button')].find(b=>b.textContent.trim()==='编辑歌单').click()");
          waitFor("!!document.querySelector('.cloud-edit')");
          waitFor(visible);
          js("[...document.querySelectorAll('.cloud-playlist-detail button')].find(b=>b.textContent.trim()==='取消编辑').click()");
          waitFor(visible);
        }
        js("document.querySelector('.cloud-playlist-detail .back-link').click()");
        Bundle progress = new Bundle();
        progress.putString("stream", "EMPTY_ARTWORK_LIST_DETAIL_REFRESH_PASS; editPanel=" + editable + "\n");
        sendStatus(0, progress);
      } else {
        Bundle progress = new Bundle();
        progress.putString("stream", "Empty-playlist device path skipped: account has no empty playlist; covered by browser regression\n");
        sendStatus(0, progress);
      }
      result.putString("stream", "CLOUD_ARTWORK_COLD_START_WEBVIEW_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "CLOUD_ARTWORK_COLD_START_WEBVIEW_FAIL: " + error.getClass().getSimpleName() + " " + error.getMessage() + "\n");
    }
    finish(code, result);
  }
}
