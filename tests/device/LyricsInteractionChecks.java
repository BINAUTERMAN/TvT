package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 在专用测试设备中验证真实旋转、中间帧和状态栏窗口，结束时恢复设置。 */
public final class LyricsInteractionChecks extends Instrumentation {
  private Context target;
  private Activity activity;
  private SharedPreferences saved;
  private Object overlay, status;
  private File output;
  private final List<String> checks = new ArrayList<>();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private void main(Runnable action) {
    Throwable[] failure = new Throwable[1];
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure[0] = error;
          }
        });
    if (failure[0] != null) throw new RuntimeException(failure[0]);
  }

  private void check(String name, boolean passed) {
    checks.add(name + ": " + (passed ? "PASS" : "FAIL"));
    Bundle progress = new Bundle();
    progress.putString("stream", checks.get(checks.size() - 1) + "\n");
    sendStatus(0, progress);
    if (!passed) throw new AssertionError(name);
  }

  private Object field(Object value, String name) {
    try {
      Field field = value.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(value);
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  private Object call(Object value, String name, Class<?>[] types, Object... args) {
    try {
      Method method = value.getClass().getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method.invoke(value, args);
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  private Object construct(String name) throws Exception {
    Class<?> type = target.getClassLoader().loadClass("cn.ting.music." + name);
    Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    return constructor.newInstance(target);
  }

  private WindowManager.LayoutParams layout() {
    return (WindowManager.LayoutParams) field(overlay, "layout");
  }

  private View panel() {
    return (View) field(overlay, "panel");
  }

  private void idle() {
    SystemClock.sleep(120);
    waitForIdleSync();
  }

  private void inside(String name) {
    Rect bounds = (Rect) field(overlay, "bounds");
    View panel = panel();
    int[] at = new int[2];
    panel.getLocationOnScreen(at);
    check(
        name,
        at[0] >= bounds.left - 2
            && at[1] >= bounds.top - 2
            && at[0] + panel.getWidth() <= bounds.right + 2
            && at[1] + panel.getHeight() <= bounds.bottom + 2);
  }

  private void rotate(int rotation, String key) {
    getUiAutomation().setRotation(rotation);
    boolean[] ready = new boolean[1];
    for (int i = 0; i < 40; i++) {
      SystemClock.sleep(100);
      main(() -> ready[0] = key.equals(field(overlay, "positionKey")));
      if (ready[0]) break;
    }
    SystemClock.sleep(600);
    idle();
    check("Rotation restores " + key + " coordinates", ready[0]);
    main(() -> inside("Visible overlay stays inside current physical screen"));
  }

  private void screenshot(String name) throws Exception {
    Bitmap image = getUiAutomation().takeScreenshot();
    try (FileOutputStream file = new FileOutputStream(new File(output, name))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, file);
    }
    image.recycle();
  }

  private MotionEvent touch(int action, float x) {
    return MotionEvent.obtain(
        SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, 30, 0);
  }

  private String javascript(WebView web, String script) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    String[] value = new String[1];
    main(
        () ->
            web.evaluateJavascript(
                script,
                result -> {
                  value[0] = result;
                  done.countDown();
                }));
    if (!done.await(3, TimeUnit.SECONDS)) throw new AssertionError("WebView response timeout");
    return value[0];
  }

  private void statusBridge() throws Exception {
    WebView web = (WebView) field(activity, "web");
    for (int i = 0; i < 40; i++) {
      if (javascript(web, "typeof window.nativeResolve === 'function'").equals("true")) break;
      SystemClock.sleep(100);
    }
    javascript(
        web,
        "window.__statusResponse=null;window.__previousResolve=window.nativeResolve;window.nativeResolve=function(id,result,error){if(id==='status-check'){window.__statusResponse={result,error};window.nativeResolve=window.__previousResolve;}else"
            + " window.__previousResolve(id,result,error);};window.Native.request('status-check','statusLyricsSettings',JSON.stringify({size:13,side:'right'}));");
    JSONObject response = null;
    for (int i = 0; i < 40; i++) {
      Object decoded =
          new JSONTokener(javascript(web, "JSON.stringify(window.__statusResponse)")).nextValue();
      if (decoded instanceof String && !decoded.equals("null")) {
        response = new JSONObject((String) decoded);
        break;
      }
      SystemClock.sleep(50);
    }
    check(
        "Status settings use the real WebView-to-native bridge",
        response != null
            && response.getJSONObject("result").getInt("size") == 13
            && saved.getString("lyricsStatusSide", "").equals("right"));
    saved.edit().putString("lyricsStatusSide", "auto").apply();
  }

  @SuppressWarnings("unchecked")
  @Override
  public void onStart() {
    target = getTargetContext();
    saved = target.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    Map<String, ?> backup = new HashMap<>(saved.getAll());
    Bundle result = new Bundle();
    int resultCode = Activity.RESULT_OK;
    try {
      output = new File(target.getExternalFilesDir(null), "lyrics-interaction");
      output.mkdirs();
      getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);
      activity =
          startActivitySync(
              new Intent()
                  .setClassName(target, "cn.ting.music.MainActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
      idle();
      statusBridge();
      saved
          .edit()
          .putBoolean("floatingLyrics", true)
          .putBoolean("lyricsLocked", false)
          .putBoolean("lyricsSmooth", true)
          .putInt("lyricsSize", 20)
          .putInt("lyricsWidth", 70)
          .putInt("lyricsBackgroundOpacity", 0)
          .putFloat("lyricsPortraitX", .15f)
          .putFloat("lyricsPortraitY", .65f)
          .putFloat("lyricsLandscapeX", .7f)
          .putFloat("lyricsLandscapeY", .2f)
          .apply();
      overlay = construct("FloatingLyrics");
      JSONObject line =
          new JSONObject().put("text", "TvT 歌词位置与动画验证").put("time", 0).put("end", 300000);
      main(
          () ->
              call(
                  overlay,
                  "showLine",
                  new Class<?>[] {
                    JSONObject.class,
                    JSONObject.class,
                    int.class,
                    long.class,
                    boolean.class,
                    float.class
                  },
                  line,
                  null,
                  0,
                  1000L,
                  false,
                  1f));
      idle();
      View original = panel();
      main(() -> inside("Portrait position uses visible screen coordinates"));
      screenshot("portrait.png");
      rotate(UiAutomation.ROTATION_FREEZE_90, "lyricsLandscape");
      check("Rotation keeps the same overlay window", original == panel());
      screenshot("landscape-90.png");
      rotate(UiAutomation.ROTATION_FREEZE_270, "lyricsLandscape");
      screenshot("landscape-270.png");
      rotate(UiAutomation.ROTATION_FREEZE_0, "lyricsPortrait");

      SeekBar[] slider = new SeekBar[1];
      int[] commits = {0};
      main(
          () -> {
            try {
              Class<?> type = target.getClassLoader().loadClass("cn.ting.music.LyricsSeekBar");
              Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
              constructor.setAccessible(true);
              slider[0] = (SeekBar) constructor.newInstance(activity);
              slider[0].setMax(100);
              slider[0].setProgress(40);
              slider[0].setOnSeekBarChangeListener(
                  new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int progress, boolean user) {}

                    public void onStartTrackingTouch(SeekBar bar) {}

                    public void onStopTrackingTouch(SeekBar bar) {
                      commits[0]++;
                    }
                  });
              activity.addContentView(slider[0], new FrameLayout.LayoutParams(500, 100));
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      idle();
      main(
          () -> {
            slider[0].onTouchEvent(touch(MotionEvent.ACTION_DOWN, 490));
            slider[0].onTouchEvent(touch(MotionEvent.ACTION_UP, 490));
            check(
                "Native track taps do not alter the value",
                slider[0].getProgress() == 40 && commits[0] == 0);
            float center =
                slider[0].getPaddingLeft()
                    + (slider[0].getWidth()
                            - slider[0].getPaddingLeft()
                            - slider[0].getPaddingRight())
                        * .4f;
            slider[0].onTouchEvent(touch(MotionEvent.ACTION_DOWN, center));
            slider[0].onTouchEvent(touch(MotionEvent.ACTION_MOVE, center + 100));
            slider[0].onTouchEvent(touch(MotionEvent.ACTION_UP, center + 100));
            check(
                "Dragging the native thumb commits once",
                slider[0].getProgress() > 40 && commits[0] == 1);
            ((ViewGroup) slider[0].getParent()).removeView(slider[0]);
            call(overlay, "toggleEditor", new Class<?>[0]);
          });
      SystemClock.sleep(180);
      idle();
      int[] start = {0};
      main(
          () -> {
            start[0] = layout().y;
            saved.edit().putBoolean("lyricsLocked", true).apply();
            call(overlay, "applyLockChange", new Class<?>[0]);
            check(
                "Lock begins without recreating or teleporting the window",
                panel() == original
                    && layout().y == start[0]
                    && field(overlay, "geometryAnimation") != null);
          });
      SystemClock.sleep(80);
      main(
          () ->
              check(
                  "Lock has a visible intermediate frame",
                  field(overlay, "geometryAnimation") != null && layout().y != start[0]));
      SystemClock.sleep(350);
      idle();
      main(
          () -> {
            check(
                "Lock finishes touch-through and releases animation",
                field(overlay, "geometryAnimation") == null
                    && (layout().flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
            inside("Locked lyric remains inside the screen");
            saved.edit().putBoolean("lyricsLocked", false).apply();
            call(overlay, "applyLockChange", new Class<?>[0]);
          });
      SystemClock.sleep(80);
      main(
          () -> {
            int before = layout().y;
            saved.edit().putBoolean("lyricsLocked", true).apply();
            call(overlay, "applyLockChange", new Class<?>[0]);
            check(
                "Reversing a lock animation starts at the current frame",
                Math.abs(layout().y - before) <= 2 && panel() == original);
          });
      SystemClock.sleep(380);
      idle();
      main(
          () -> {
            saved.edit().putBoolean("lyricsLocked", false).apply();
            call(overlay, "applyLockChange", new Class<?>[0]);
          });
      SystemClock.sleep(380);
      idle();
      int[] edgeStart = {0};
      main(
          () -> {
            Rect bounds = (Rect) field(overlay, "bounds");
            edgeStart[0] = bounds.left + 40;
            layout().x = edgeStart[0];
            call(overlay, "updateLayout", new Class<?>[0]);
            call(overlay, "savePosition", new Class<?>[] {boolean.class}, true);
            check(
                "Snapping starts at the dragged position",
                layout().x == edgeStart[0] && field(overlay, "snapAnimation") != null);
          });
      SystemClock.sleep(80);
      main(
          () -> {
            Rect bounds = (Rect) field(overlay, "bounds");
            check(
                "Snapping has visible intermediate positions",
                layout().x > bounds.left && layout().x < edgeStart[0]);
          });
      SystemClock.sleep(380);
      idle();
      main(
          () -> {
            Rect bounds = (Rect) field(overlay, "bounds");
            check(
                "Snapping reaches the exact edge and releases animation",
                layout().x == bounds.left && field(overlay, "snapAnimation") == null);
          });
      screenshot("locked-and-snapped.png");
      main(() -> call(overlay, "hide", new Class<?>[0]));
      idle();

      status = construct("StatusBarLyrics");
      saved
          .edit()
          .putBoolean("floatingLyrics", false)
          .putBoolean("lyricsStatusEnabled", true)
          .putString("lyricsStatusColor", "#ff00ff")
          .putString("lyricsStatusSungColor", "#ff00ff")
          .putInt("lyricsStatusSize", 12)
          .putInt("lyricsStatusVertical", 0)
          .apply();
      main(
          () ->
              call(
                  status,
                  "show",
                  new Class<?>[] {
                    JSONObject.class,
                    JSONObject.class,
                    long.class,
                    boolean.class,
                    float.class,
                    String.class
                  },
                  line,
                  null,
                  1000L,
                  true,
                  1f,
                  ""));
      idle();
      main(
          () -> {
            View view = (View) field(status, "panel");
            int[] point = new int[2];
            view.getLocationOnScreen(point);
            int resource =
                target.getResources().getIdentifier("status_bar_height", "dimen", "android");
            int bar = target.getResources().getDimensionPixelSize(resource);
            check(
                "Status lyrics attach independently above desktop safe area",
                view.isAttachedToWindow()
                    && point[1] < bar
                    && !saved.getBoolean("floatingLyrics", true));
            WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) field(status, "layout");
            check(
                "Status lyrics are single-line and touch-through",
                (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
                    && params.alpha <= .8f);
            DisplayCutout cutout =
                target.getSystemService(WindowManager.class).getDefaultDisplay().getCutout();
            boolean avoidsCutout = true;
            int clockOffset = 0;
            if (cutout != null)
              for (Rect rect : cutout.getBoundingRects()) {
                avoidsCutout &=
                    !Rect.intersects(
                        rect,
                        new Rect(
                            point[0],
                            point[1],
                            point[0] + view.getWidth(),
                            point[1] + view.getHeight()));
                if (rect.left == 0 && rect.top == 0)
                  clockOffset = Math.max(clockOffset, rect.right);
              }
            check(
                "Status lyrics avoid camera cutouts and relocated clock area",
                avoidsCutout
                    && point[0]
                        >= clockOffset
                            + Math.round(56 * target.getResources().getDisplayMetrics().density));
          });
      screenshot("status-bar.png");
      Bitmap statusImage = getUiAutomation().takeScreenshot();
      int magenta = 0;
      for (int y = 0; y < Math.min(statusImage.getHeight(), 100); y++)
        for (int x = 0; x < statusImage.getWidth(); x++) {
          int pixel = statusImage.getPixel(x, y);
          if (Color.red(pixel) > 150 && Color.blue(pixel) > 150 && Color.green(pixel) < 130)
            magenta++;
        }
      statusImage.recycle();
      check("Status lyric pixels are visible in the actual status-bar screenshot", magenta > 10);
      main(
          () -> {
            int resource =
                target
                    .getResources()
                    .getIdentifier("ic_notification", "drawable", target.getPackageName());
            android.graphics.drawable.Drawable icon = target.getDrawable(resource);
            Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 96, 96);
            icon.draw(new Canvas(bitmap));
            int left = 96, right = 0, top = 96, bottom = 0;
            for (int y = 0; y < 96; y++)
              for (int x = 0; x < 96; x++)
                if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                  left = Math.min(left, x);
                  right = Math.max(right, x);
                  top = Math.min(top, y);
                  bottom = Math.max(bottom, y);
                }
            check(
                "Notification icon renders the wide TvT wordmark",
                right - left > (bottom - top) * 1.8f);
            try (FileOutputStream file =
                new FileOutputStream(new File(output, "notification-icon.png"))) {
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
            bitmap.recycle();
          });
      main(
          () ->
              call(
                  status,
                  "show",
                  new Class<?>[] {
                    JSONObject.class,
                    JSONObject.class,
                    long.class,
                    boolean.class,
                    float.class,
                    String.class
                  },
                  line,
                  null,
                  1000L,
                  false,
                  1f,
                  ""));
      idle();
      main(
          () -> {
            check("Pausing removes the status-bar window", field(status, "panel") == null);
            call(status, "preview", new Class<?>[0]);
            call(status, "hide", new Class<?>[0]);
            Handler handler = (Handler) field(status, "handler");
            Runnable end = (Runnable) field(status, "endPreview");
            check("Closing cancels the preview expiry callback", !handler.hasCallbacks(end));
            try {
              Class<?> type = target.getClassLoader().loadClass("cn.ting.music.FloatingLyrics");
              Method method = type.getDeclaredMethod("settings", Context.class, JSONObject.class);
              method.setAccessible(true);
              JSONObject restored =
                  (JSONObject)
                      method.invoke(null, target, new JSONObject().put("resetDefaults", true));
              check(
                  "Restore defaults resets style and positions but preserves feature switches",
                  restored.getInt("size") == 20
                      && !restored.getBoolean("locked")
                      && !saved.contains("lyricsLandscapeX")
                      && saved.getBoolean("lyricsStatusEnabled", false));
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      result.putString("stream", "LYRICS_INTERACTION_PASS\n");
    } catch (Throwable failure) {
      resultCode = Activity.RESULT_CANCELED;
      result.putString("stream", "LYRICS_INTERACTION_FAIL: " + failure + "\n");
    } finally {
      main(
          () -> {
            if (overlay != null) call(overlay, "hide", new Class<?>[0]);
            if (status != null) call(status, "hide", new Class<?>[0]);
          });
      getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE);
      SharedPreferences.Editor editor = saved.edit().clear();
      for (Map.Entry<String, ?> entry : backup.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
      }
      editor.commit();
      try (FileOutputStream file = new FileOutputStream(new File(output, "results.txt"))) {
        file.write(String.join("\n", checks).getBytes("UTF-8"));
      } catch (Exception ignored) {
      }
    }
    finish(resultCode, result);
  }
}
