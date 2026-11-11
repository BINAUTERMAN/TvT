package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 使用自建时间数据验证原生绘制，不请求音乐接口或读取账号。 */
public final class LyricsChecks extends Instrumentation {
  private Context target;
  private File output;
  private Activity activity;
  private Method show;
  private View lyrics;
  private final List<String> results = new ArrayList<>();

  private void onMain(Runnable action) {
    Throwable[] failure = new Throwable[1];
    super.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure[0] = error;
          }
        });
    if (failure[0] != null) throw new RuntimeException(failure[0]);
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private void check(String name, boolean passed) {
    results.add(name + ": " + (passed ? "PASS" : "FAIL"));
    Bundle status = new Bundle();
    status.putString("stream", results.get(results.size() - 1) + "\n");
    sendStatus(0, status);
    if (!passed) throw new AssertionError(name);
  }

  private Method method(Class<?> type, String name, Class<?>... args) throws Exception {
    Method result = type.getDeclaredMethod(name, args);
    result.setAccessible(true);
    return result;
  }

  private Object field(Object value, String name) throws Exception {
    Field result = value.getClass().getDeclaredField(name);
    result.setAccessible(true);
    return result.get(value);
  }

  private JSONObject line(String text, long start) throws Exception {
    JSONArray words = new JSONArray();
    int cursor = 0, count = 0;
    while (cursor < text.length()) {
      int end = text.offsetByCodePoints(cursor, 1);
      words.put(
          new JSONObject()
              .put("text", text.substring(cursor, end))
              .put("time", start + count * 800)
              .put("end", start + (count + 1) * 800));
      cursor = end;
      count++;
    }
    return new JSONObject()
        .put("text", text)
        .put("time", start / 1000d)
        .put("end", start + count * 800)
        .put("words", words);
  }

  private void feed(
      JSONObject line, JSONObject next, int index, long position, boolean playing, float speed)
      throws Exception {
    show.invoke(lyrics, line, next, index, position, playing, speed);
  }

  private Bitmap render(String name, int width) throws Exception {
    lyrics.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST));
    lyrics.layout(0, 0, width, lyrics.getMeasuredHeight());
    Bitmap bitmap = Bitmap.createBitmap(width, lyrics.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
    lyrics.draw(new Canvas(bitmap));
    if (name != null)
      try (FileOutputStream file = new FileOutputStream(new File(output, name))) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
      }
    return bitmap;
  }

  private int cyan(Bitmap image, boolean bottom) {
    int count = 0, from = bottom ? image.getHeight() / 2 : 0;
    int to = bottom ? image.getHeight() : image.getHeight() / 2;
    for (int y = from; y < to; y++)
      for (int x = 0; x < image.getWidth(); x++) {
        int color = image.getPixel(x, y);
        if (Color.alpha(color) > 200 && Color.red(color) < 40 && Color.blue(color) > 180) count++;
      }
    return count;
  }

  private JSONObject bridge(WebView web, JSONObject payload) throws Exception {
    CountDownLatch sent = new CountDownLatch(1);
    onMain(
        () -> {
          String script =
              "window.__lyricsResult=null;window.__lyricsResolve=window.nativeResolve;window.nativeResolve=function(id,result,error){if(id==='lyrics-verification'){window.__lyricsResult={result,error};window.nativeResolve=window.__lyricsResolve;}else"
                  + " window.__lyricsResolve(id,result,error);};"
                  + "window.Native.request('lyrics-verification','floatingSettings',"
                  + JSONObject.quote(payload.toString())
                  + ");";
          web.evaluateJavascript(script, value -> sent.countDown());
        });
    if (!sent.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView request timeout");
    for (int i = 0; i < 50; i++) {
      CountDownLatch read = new CountDownLatch(1);
      String[] result = new String[1];
      onMain(
          () ->
              web.evaluateJavascript(
                  "JSON.stringify(window.__lyricsResult)",
                  value -> {
                    result[0] = value;
                    read.countDown();
                  }));
      if (!read.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView response timeout");
      Object decoded = new JSONTokener(result[0]).nextValue();
      if (decoded instanceof String && !decoded.equals("null"))
        return new JSONObject((String) decoded);
      SystemClock.sleep(50);
    }
    throw new AssertionError("Native bridge response timeout");
  }

  @SuppressWarnings("unchecked")
  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    target = getTargetContext();
    SharedPreferences saved = target.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    Map<String, ?> before = new HashMap<>(saved.getAll());
    final Object[] overlay = new Object[1];
    final View[] removedPanel = new View[1];
    try {
      output = new File(target.getExternalFilesDir(null), "lyrics-verification");
      output.mkdirs();
      activity =
          startActivitySync(
              new Intent()
                  .setClassName(target, "cn.ting.music.MainActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      WebView web = (WebView) field(activity, "web");
      boolean ready = false;
      for (int i = 0; i < 80 && !ready; i++) {
        CountDownLatch loaded = new CountDownLatch(1);
        boolean[] state = new boolean[1];
        onMain(
            () ->
                web.evaluateJavascript(
                    "typeof window.nativeResolve === 'function' &&"
                        + " !!document.querySelector('.topbar')",
                    value -> {
                      state[0] = value.equals("true");
                      loaded.countDown();
                    }));
        loaded.await(2, TimeUnit.SECONDS);
        ready = state[0];
        if (!ready) SystemClock.sleep(100);
      }
      check("App WebView and native bridge initialize", ready);
      JSONObject response =
          bridge(
              web,
              new JSONObject()
                  .put("size", 32)
                  .put("highlightColor", "#123abc")
                  .put("smooth", true)
                  .put("mode", "next"));
      check(
          "Real WebView bridge persists native lyrics settings",
          response.getJSONObject("result").getInt("size") == 32
              && saved.getString("lyricsHighlightColor", "").equals("#123abc"));
      JSONObject invalid =
          bridge(web, new JSONObject().put("size", 40).put("textColor", "invalid"));
      check(
          "Invalid color rejects the entire native update",
          invalid.optString("error").contains("颜色") && saved.getInt("lyricsSize", 0) == 32);
      saved
          .edit()
          .putInt("lyricsSize", 20)
          .putString("lyricsMode", "next")
          .putString("lyricsHighlightColor", "#00d9e9")
          .putString("lyricsTextColor", "#8fffad")
          .putBoolean("lyricsGradient", false)
          .putInt("lyricsOutline", 0)
          .putInt("lyricsBackgroundOpacity", 0)
          .putBoolean("lyricsLocked", false)
          .putBoolean("lyricsSmooth", true)
          .apply();
      Class<?> viewType = target.getClassLoader().loadClass("cn.ting.music.LyricsOverlayView");
      Constructor<?> constructor =
          viewType.getDeclaredConstructor(Context.class, SharedPreferences.class);
      constructor.setAccessible(true);
      show =
          method(
              viewType,
              "showLine",
              JSONObject.class,
              JSONObject.class,
              int.class,
              long.class,
              boolean.class,
              float.class);
      JSONObject first = line("让音乐在此刻流动", 1000);
      JSONObject second = line("下一句陪你走过日常", first.getLong("end"));
      onMain(
          () -> {
            try {
              lyrics = (View) constructor.newInstance(activity, saved);
              feed(first, second, 0, 1000, false, 1);
              Bitmap empty = render("native-sweep-0.png", 600);
              Object row = field(lyrics, "current");
              Method fill = method(row.getClass(), "fill", long.class);
              float begin = (Float) fill.invoke(row, 1000L),
                  middle = (Float) fill.invoke(row, 1400L),
                  end = (Float) fill.invoke(row, 1800L);
              check(
                  "Native glyph highlight advances continuously within a word",
                  begin < middle && middle < end);
              feed(first, second, 0, 1400, false, 1);
              Bitmap half = render("native-sweep-half.png", 600);
              check(
                  "Canvas pixels change within a single character",
                  !empty.sameAs(half) && cyan(half, false) > 0);
              for (int frame = 0; frame < 128; frame++) {
                long time = 1000 + frame * 100L;
                if (time < first.getLong("end")) feed(first, second, 0, time, false, 1);
                else feed(second, null, 1, time, false, 1);
                render(String.format(java.util.Locale.ROOT, "preview-%03d.png", frame), 600)
                    .recycle();
              }
              feed(second, null, 1, second.getLong("end") - 100, false, 1);
              Bitmap odd = render("native-double-bottom.png", 600);
              check("Double line alternates the active row", cyan(odd, true) > cyan(odd, false));
              JSONObject longLine = line("这是一段用于验证长句横向移动而不省略内容的测试文字😀", 1000);
              feed(longLine, null, 0, 1000, false, 1);
              render("native-long-start.png", 300);
              Object longRow = field(lyrics, "current");
              Method scroll = method(longRow.getClass(), "scroll", long.class, float.class);
              float offset = (Float) scroll.invoke(longRow, longLine.getLong("end") - 1, 300f);
              check("Long line scrolls to its end instead of ellipsizing", offset > 0);
              feed(longLine, null, 0, longLine.getLong("end") - 1, false, 1);
              render("native-long-end.png", 300);
              feed(first, second, 0, 1100, true, 2);
              activity.addContentView(lyrics, new FrameLayout.LayoutParams(600, 240, Gravity.TOP));
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      SystemClock.sleep(150);
      onMain(
          () -> {
            try {
              long position = (Long) method(viewType, "position").invoke(lyrics);
              check("Native frame clock follows playback speed", position >= 1300);
              method(viewType, "stop").invoke(lyrics);
              check(
                  "Pause cancels scheduled animation frames",
                  !((Boolean) field(lyrics, "playing")) && !((Boolean) field(lyrics, "scheduled")));
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      long frozen = (Long) method(viewType, "position").invoke(lyrics);
      SystemClock.sleep(150);
      check(
          "Paused lyric time remains frozen",
          frozen == (Long) method(viewType, "position").invoke(lyrics));
      onMain(
          () -> {
            try {
              feed(first, second, 0, 1100, true, 1);
              lyrics.setVisibility(View.INVISIBLE);
              check(
                  "Hiding cancels native frame callbacks", !((Boolean) field(lyrics, "scheduled")));
              lyrics.setVisibility(View.VISIBLE);
              ((ViewGroup) lyrics.getParent()).removeView(lyrics);
              check(
                  "Detach releases native frame callbacks",
                  !((Boolean) field(lyrics, "playing")) && !((Boolean) field(lyrics, "scheduled")));
              Class<?> type = target.getClassLoader().loadClass("cn.ting.music.FloatingLyrics");
              Constructor<?> overlayConstructor = type.getDeclaredConstructor(Context.class);
              overlayConstructor.setAccessible(true);
              overlay[0] = overlayConstructor.newInstance(target);
              saved.edit().putBoolean("floatingLyrics", true).putInt("lyricsSize", 48).apply();
              method(
                      type,
                      "showLine",
                      JSONObject.class,
                      JSONObject.class,
                      int.class,
                      long.class,
                      boolean.class,
                      float.class)
                  .invoke(overlay[0], first, second, 0, 1300, true, 1);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      waitForIdleSync();
      onMain(
          () -> {
            try {
              Class<?> type = overlay[0].getClass();
              Object panel = field(overlay[0], "panel");
              check(
                  "Actual Android overlay attaches with granted permission",
                  panel != null && ((View) panel).isAttachedToWindow());
              method(type, "toggleEditor").invoke(overlay[0]);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      SystemClock.sleep(380);
      waitForIdleSync();
      onMain(
          () -> {
            try {
              Class<?> type = overlay[0].getClass();
              View panel = (View) field(overlay[0], "panel");
              View controls = (View) field(overlay[0], "tools");
              Rect bounds = (Rect) field(overlay[0], "bounds");
              WindowManager.LayoutParams layout =
                  (WindowManager.LayoutParams) field(overlay[0], "layout");
              check(
                  "Desktop controls expand inside usable screen bounds",
                  controls.getVisibility() == View.VISIBLE
                      && controls.getHeight() > 0
                      && panel.getWidth() <= bounds.width()
                      && layout.y >= bounds.top
                      && layout.y + panel.getHeight() <= bounds.bottom);
              check(
                  "Large fonts keep every desktop control row visible",
                  ((ViewGroup) controls).getChildAt(2).getHeight()
                      >= 40 * controls.getResources().getDisplayMetrics().density);
              Bitmap editor =
                  Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
              panel.draw(new Canvas(editor));
              try (FileOutputStream file =
                  new FileOutputStream(new File(output, "native-editor.png"))) {
                editor.compress(Bitmap.CompressFormat.PNG, 100, file);
              }
              layout.x = bounds.left + 12;
              method(type, "savePosition", boolean.class).invoke(overlay[0], true);
              String positionKey = (String) field(overlay[0], "positionKey");
              check(
                  "Four-edge snapping stores relative screen position",
                  saved.getFloat(positionKey + "X", -1) == 0);
              method(type, "hide").invoke(overlay[0]);
              check(
                  "Closing cancels edge settling animation",
                  field(overlay[0], "snapAnimation") == null);
              removedPanel[0] = panel;
              saved.edit().putBoolean("lyricsLocked", true).apply();
              method(
                      type,
                      "showLine",
                      JSONObject.class,
                      JSONObject.class,
                      int.class,
                      long.class,
                      boolean.class,
                      float.class)
                  .invoke(overlay[0], first, second, 0, 1400, false, 1);
              WindowManager.LayoutParams lockedLayout =
                  (WindowManager.LayoutParams) field(overlay[0], "layout");
              check(
                  "Locked lyrics use touch-through window flags",
                  (lockedLayout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
                      && lockedLayout.alpha <= .8f);
              method(type, "hide").invoke(overlay[0]);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      waitForIdleSync();
      check("Closing removes the overlay window", !removedPanel[0].isAttachedToWindow());
      result.putString("stream", "LYRICS_NATIVE_PASS\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "LYRICS_NATIVE_FAIL: " + error + "\n");
    } finally {
      onMain(
          () -> {
            try {
              if (overlay[0] != null) method(overlay[0].getClass(), "hide").invoke(overlay[0]);
              if (lyrics != null && lyrics.getParent() instanceof ViewGroup)
                ((ViewGroup) lyrics.getParent()).removeView(lyrics);
            } catch (Exception ignored) {
            }
          });
      SharedPreferences.Editor editor = saved.edit().clear();
      for (Map.Entry<String, ?> entry : before.entrySet()) {
        Object value = entry.getValue();
        String key = entry.getKey();
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
      }
      editor.commit();
      try (FileOutputStream file = new FileOutputStream(new File(output, "results.txt"))) {
        file.write(String.join("\n", results).getBytes("UTF-8"));
      } catch (Exception ignored) {
      }
    }
    finish(code, result);
  }
}
