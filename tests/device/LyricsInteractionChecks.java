package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.media.session.*;
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

  private void setField(Object value, String name, Object replacement) throws Exception {
    Field field = value.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(value, replacement);
  }

  private void notificationState(Object service, String label, String iconName) {
    call(service, "showNotification", new Class<?>[0]);
    MediaSession session = (MediaSession) field(service, "session");
    List<PlaybackState.CustomAction> actions = session.getController().getPlaybackState().getCustomActions();
    int icon = target.getResources().getIdentifier(iconName, "drawable", target.getPackageName());
    check(
        "System media panel places " + label + " at the right edge",
        actions.size() == 2 && actions.get(0).getAction().equals("favorite")
            && actions.get(1).getAction().equals("lyrics")
            && actions.get(1).getName().equals(label) && actions.get(1).getIcon() == icon);
    NotificationManager manager = target.getSystemService(NotificationManager.class);
    Notification notification = null;
    for (android.service.notification.StatusBarNotification entry : manager.getActiveNotifications())
      if (entry.getId() == 1) notification = entry.getNotification();
    check(
        "Playback notification matches the media-session lyric state",
        notification != null && notification.actions.length == 5
            && notification.actions[4].title.equals(label)
            && notification.actions[4].getIcon().getResId() == icon);
    check("Status notification carries fresh TvT pixels instead of a legacy resource reference",
        notification.getSmallIcon().getType() == android.graphics.drawable.Icon.TYPE_BITMAP);
  }

  private void dragOpacity(int opacity) {
    float[] points = new float[3];
    main(() -> {
      ViewGroup rows = (ViewGroup) field(overlay, "tools");
      SeekBar slider = (SeekBar) ((ViewGroup) rows.getChildAt(2)).getChildAt(1);
      int[] at = new int[2];
      slider.getLocationOnScreen(at);
      int width = slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight();
      points[0] = at[0] + slider.getPaddingLeft()
          + width * (slider.getProgress() - slider.getMin()) / (float) (slider.getMax() - slider.getMin());
      points[1] = at[0] + slider.getPaddingLeft()
          + width * (opacity - slider.getMin()) / (float) (slider.getMax() - slider.getMin());
      points[2] = at[1] + slider.getHeight() / 2f;
    });
    long down = SystemClock.uptimeMillis();
    for (int step = 0; step <= 9; step++) {
      int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 9 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
      MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
          points[0] + (points[1] - points[0]) * Math.min(step, 8) / 8f, points[2], 0);
      event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      sendPointerSync(event);
      event.recycle();
    }
    idle();
    check("Real touchscreen drag reaches opacity " + opacity, saved.getInt("lyricsOpacity", 0) == opacity);
  }

  @SuppressWarnings("unchecked")
  private void minimumOpacityAndNotification(JSONObject line) throws Exception {
    Class<?> type = target.getClassLoader().loadClass("cn.ting.music.PlaybackService");
    Object service = type.getField("instance").get(null);
    Object oldQueue = field(service, "queue"), oldOverlay = field(service, "floatingLyrics");
    int oldIndex = (Integer) field(service, "index");
    List<JSONObject> lines = (List<JSONObject>) field(service, "lyricLines");
    List<Long> times = (List<Long>) field(service, "lyricTimes");
    List<JSONObject> oldLines = new ArrayList<>(lines);
    List<Long> oldTimes = new ArrayList<>(times);
    SharedPreferences playback = target.getSharedPreferences("playback", Context.MODE_PRIVATE);
    Map<String, ?> playbackBefore = new HashMap<>(playback.getAll());
    try {
      main(() -> {
        try {
          setField(service, "queue", new JSONArray().put(new JSONObject().put("title", "歌词交互回归验证")));
          setField(service, "index", 0);
          setField(service, "floatingLyrics", overlay);
          lines.clear(); lines.add(line);
          times.clear(); times.add(0L);
          saved.edit().putBoolean("lyricsStatusEnabled", false).apply();
          if (!(Boolean) field(overlay, "editing")) call(overlay, "toggleEditor", new Class<?>[0]);
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      SystemClock.sleep(380);
      idle();
      View original = panel();
      View controls = (View) field(overlay, "tools");
      for (int opacity : new int[] {30, 100, 30, 100, 30}) {
        main(() -> {
          saved.edit().putInt("lyricsOpacity", opacity).apply();
          JSONObject update = new JSONObject();
          try { update.put("opacity", opacity); } catch (Exception error) { throw new RuntimeException(error); }
          call(service, "configureFloatingLyrics", new Class<?>[] {JSONObject.class}, update);
          check("Opacity " + opacity + " retains the live window and opaque controls",
              panel() == original && field(overlay, "tools") == controls && layout().alpha == 1
                  && controls.getAlpha() == 1
                  && (layout().flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0);
        });
        idle();
      }
      main(() -> {
        ViewGroup rows = (ViewGroup) controls;
        SeekBar slider = (SeekBar) ((ViewGroup) rows.getChildAt(2)).getChildAt(1);
        Bundle arguments = new Bundle();
        arguments.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 100);
        slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments);
        check("Minimum opacity still accepts a native slider action without replacing controls",
            saved.getInt("lyricsOpacity", 0) == 100 && panel() == original && field(overlay, "tools") == controls);
      });
      for (int opacity : new int[] {30, 100, 30, 100}) dragOpacity(opacity);
      main(() -> {
        check("Repeated touchscreen drags preserve the current controls", panel() == original && field(overlay, "tools") == controls);
        notificationState(service, "关闭桌面歌词", "ic_lyrics_active");
        ((ViewGroup) ((ViewGroup) controls).getChildAt(0)).getChildAt(0).performClick();
        notificationState(service, "解锁桌面歌词", "ic_lyrics_unlock");
      });
      SystemClock.sleep(380);
      idle();
      statusScreenshot();
      main(() -> {
        MediaSession session = (MediaSession) field(service, "session");
        session.getController().getTransportControls().sendCustomAction("lyrics", null);
      });
      SystemClock.sleep(450);
      idle();
      main(() -> {
        check("System media lyric action unlocks the same window and restores touch",
            !saved.getBoolean("lyricsLocked", true) && saved.getBoolean("floatingLyrics", false)
                && panel() == original && (layout().flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0);
        notificationState(service, "关闭桌面歌词", "ic_lyrics_active");
        call(service, "execute", new Class<?>[] {String.class, JSONObject.class}, "lyrics", new JSONObject());
        notificationState(service, "开启桌面歌词", "ic_lyrics");
        check("The enabled lyric action closes the desktop overlay", field(overlay, "panel") == null);
      });
    } finally {
      main(() -> {
        try {
          call(overlay, "hide", new Class<?>[0]);
          setField(service, "queue", oldQueue);
          setField(service, "index", oldIndex);
          setField(service, "floatingLyrics", oldOverlay);
          lines.clear(); lines.addAll(oldLines);
          times.clear(); times.addAll(oldTimes);
          SharedPreferences.Editor restore = playback.edit();
          for (String key : new String[] {"queue", "index", "mode"}) {
            Object previous = playbackBefore.get(key);
            if (previous instanceof String) restore.putString(key, (String) previous);
            else if (previous instanceof Integer) restore.putInt(key, (Integer) previous);
            else restore.remove(key);
          }
          restore.commit();
          call(service, "showNotification", new Class<?>[0]);
          call(service, "emit", new Class<?>[0]);
          if (((JSONArray) oldQueue).length() == 0) target.getSystemService(NotificationManager.class).cancel(1);
        } catch (Exception error) { throw new RuntimeException(error); }
      });
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

  private void statusScreenshot() throws Exception {
    Bitmap image = getUiAutomation().takeScreenshot();
    int height = Math.round(48 * target.getResources().getDisplayMetrics().density);
    Bitmap bar = Bitmap.createBitmap(image, 0, 0, image.getWidth(), Math.min(height, image.getHeight()));
    try (FileOutputStream file = new FileOutputStream(new File(output, "published-status-icon.png"))) {
      bar.compress(Bitmap.CompressFormat.PNG, 100, file);
    }
    bar.recycle();
    image.recycle();
  }

  private void stableLandscape() throws Exception {
    main(
        () -> {
          saved.edit().putFloat("lyricsLandscapeY", .006f).apply();
          call(overlay, "onDisplayChanged", new Class<?>[0]);
        });
    idle();
    Rect[] initial = new Rect[1];
    int[] startY = new int[1];
    main(
        () -> {
          initial[0] = new Rect((Rect) field(overlay, "bounds"));
          startY[0] = layout().y;
        });
    boolean[] stable = {true};
    int resource = target.getResources().getIdentifier("status_bar_height", "dimen", "android");
    int bar = target.getResources().getDimensionPixelSize(resource);
    for (int i = 0; i < 24; i++) {
      final int top = i % 2 == 0 ? 0 : bar;
      final boolean immersive = i < 12;
      main(
          () -> {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (immersive) controller.hide(WindowInsets.Type.systemBars());
            else controller.show(WindowInsets.Type.systemBars());
            WindowInsets local =
                new WindowInsets.Builder()
                    .setInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars(), Insets.of(0, top, 0, 0))
                    .build();
            call(overlay, "updateDisplay", new Class<?>[] {WindowInsets.class}, local);
            stable[0] &= initial[0].equals(field(overlay, "bounds")) && layout().y == startY[0];
            panel().requestApplyInsets();
          });
      SystemClock.sleep(70);
    }
    check("Window-local insets cannot move the landscape lyric between two positions", stable[0]);
    main(
        () -> {
          int[] at = new int[2];
          panel().getLocationOnScreen(at);
          check(
              "Fullscreen system-bar changes keep the actual lyric window at the same height",
              Math.abs(at[1] - startY[0]) <= 1 && initial[0].equals(field(overlay, "bounds")));
        });
    screenshot("landscape-stable.png");
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
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("WebView response timeout");
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

  private void drawerMotion() throws Exception {
    WebView web = (WebView) field(activity, "web");
    boolean reduced = javascript(web, "matchMedia('(prefers-reduced-motion: reduce)').matches").equals("true");
    javascript(web,
        "window.__drawerFrames=[];window.__drawerRecorder=function(e){"
            + "if(e.propertyName!=='opacity'||!e.target.matches('.sheet-panel'))return;"
            + "const el=e.target;requestAnimationFrame(()=>requestAnimationFrame(()=>"
            + "window.__drawerFrames.push({name:el.getAttribute('aria-label'),opacity:Number(getComputedStyle(el).opacity)})));};"
            + "document.addEventListener('transitionrun',window.__drawerRecorder);"
            + "document.querySelector('.topbar button').click();");
    SystemClock.sleep(500);
    check("Actual WebView opens the account drawer",
        javascript(web, "!!document.querySelector('.sheet-panel[aria-label=account]:not([inert])')").equals("true"));
    javascript(web,
        "[...document.querySelectorAll('.sheet-panel[aria-label=account] button')]"
            + ".find(button=>button.textContent.trim()==='偏好设置').click();");
    SystemClock.sleep(650);
    check("Actual WebView switches to a usable settings drawer",
        javascript(web, "!!document.querySelector('.sheet-panel[aria-label=settings]:not([inert])')").equals("true"));
    if (!reduced)
      check("Actual WebView renders intermediate opacity for both initial and nested drawers",
          javascript(web, "['account','settings'].every(name=>window.__drawerFrames.some(frame=>"
              + "frame.name===name&&frame.opacity>0&&frame.opacity<1))").equals("true"));
    else check("Actual WebView respects reduced-motion settings",
        javascript(web, "document.getAnimations().filter(a=>a.playState==='running').length===0").equals("true"));
    javascript(web,
        "document.querySelector('.sheet-panel .close-sheet').click();"
            + "document.removeEventListener('transitionrun',window.__drawerRecorder);"
            + "delete window.__drawerRecorder;delete window.__drawerFrames;");
    SystemClock.sleep(300);
  }

  private void lyricSettings() throws Exception {
    WebView web = (WebView) field(activity, "web");
    javascript(web, "document.querySelector('.topbar button').click()");
    SystemClock.sleep(300);
    javascript(web, "[...document.querySelectorAll('.sheet-panel button')].find(button=>button.textContent.trim()==='偏好设置').click()");
    SystemClock.sleep(550);
    javascript(web, "[...document.querySelectorAll('.sheet-panel button')].find(button=>button.textContent.trim()==='桌面歌词设置').click()");
    SystemClock.sleep(650);
    check("Lyrics settings use a native PNG preview and custom selection controls",
        javascript(web, "!!document.querySelector('.floating-preview[src^=\"data:image/png;base64,\"]')&&"
            + "document.querySelectorAll('.floating-settings select').length===0&&!!document.querySelector('[role=combobox][aria-label=歌词显示模式]')").equals("true"));
    javascript(web, "window.__foldFrames=[];window.__foldRecorder=e=>{if(e.propertyName==='opacity'&&e.target.matches('.disclosure-body')){const el=e.target;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__foldFrames.push(Number(getComputedStyle(el).opacity))));}};"
        + "document.addEventListener('transitionrun',window.__foldRecorder);document.querySelectorAll('.lyrics-details summary').forEach(summary=>summary.click());");
    SystemClock.sleep(350);
    check("Both lyric customization sections expand inside the actual WebView",
        javascript(web, "[...document.querySelectorAll('.lyrics-details')].every(details=>details.open&&details.querySelector('.disclosure-body').getBoundingClientRect().height>0)").equals("true"));
    if (!javascript(web, "matchMedia('(prefers-reduced-motion: reduce)').matches").equals("true"))
      check("Lyric customization folds render intermediate animation frames",
          javascript(web, "window.__foldFrames.some(opacity=>opacity>0&&opacity<1)").equals("true"));
    screenshot("lyrics-settings-native.png");
    int size = saved.getInt("lyricsSize", 20), width = saved.getInt("lyricsWidth", 88);
    javascript(web,
        "window.__previewResponse=null;window.__previewResolve=window.nativeResolve;window.nativeResolve=(id,result,error)=>{if(id==='preview-check'){window.__previewResponse={result,error};window.nativeResolve=window.__previewResolve;}else window.__previewResolve(id,result,error);};"
            + "window.Native.request('preview-check','floatingPreview',JSON.stringify({size:28,width:67,mode:'translation',outline:0}));");
    JSONObject response = null;
    for (int index = 0; index < 40; index++) {
      Object value = new JSONTokener(javascript(web, "JSON.stringify(window.__previewResponse)")).nextValue();
      if (value instanceof String && !value.equals("null")) { response = new JSONObject((String) value); break; }
      SystemClock.sleep(50);
    }
    check("Native preview bridge renders without persisting draft parameters",
        response != null && response.optString("error").isEmpty()
            && saved.getInt("lyricsSize", 20) == size && saved.getInt("lyricsWidth", 88) == width);
    JSONObject preview = response.getJSONObject("result");
    byte[] bytes = android.util.Base64.decode(preview.getString("image").split(",", 2)[1], android.util.Base64.DEFAULT);
    Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    check("Native preview returns a decodable transparent image at its reported dimensions",
        image != null && image.getWidth() == preview.getInt("width") && image.getHeight() == preview.getInt("height") && image.hasAlpha());
    try (FileOutputStream file = new FileOutputStream(new File(output, "native-settings-preview.png"))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, file);
    }
    image.recycle();
    javascript(web, "document.removeEventListener('transitionrun',window.__foldRecorder);delete window.__foldFrames;delete window.__foldRecorder;document.querySelector('.close-sheet').click()");
    SystemClock.sleep(300);
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
      drawerMotion();
      lyricSettings();
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
          .putFloat("lyricsLandscapeY", .006f)
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
      stableLandscape();
      rotate(UiAutomation.ROTATION_FREEZE_270, "lyricsLandscape");
      screenshot("landscape-270.png");
      stableLandscape();
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
      SystemClock.sleep(380);
      idle();
      main(
          () -> {
            ViewGroup rows = (ViewGroup) field(overlay, "tools");
            ((ViewGroup) rows.getChildAt(0)).getChildAt(4).performClick();
          });
      idle();
      android.view.accessibility.AccessibilityNodeInfo menuRoot =
          getUiAutomation().getRootInActiveWindow();
      check(
          "More styles opens a real accessible native menu above the overlay",
          menuRoot != null && !menuRoot.findAccessibilityNodeInfosByText("关闭桌面歌词").isEmpty());
      SystemClock.sleep(220);
      screenshot("desktop-style-menu.png");
      main(() -> ((PopupWindow) field(field(overlay, "tools"), "overflow")).dismiss());
      idle();
      int[] expandedHeight = new int[1];
      main(
          () -> {
            expandedHeight[0] = panel().getHeight();
            ViewGroup toolRows = (ViewGroup) field(overlay, "tools");
            SeekBar fontSlider = (SeekBar) ((ViewGroup) toolRows.getChildAt(1)).getChildAt(1);
            SeekBar opacitySlider = (SeekBar) ((ViewGroup) toolRows.getChildAt(2)).getChildAt(1);
            check(
                "Native sliders expose their actual accessible parameter ranges",
                fontSlider.getMin() == 12
                    && fontSlider.getMax() == 48
                    && opacitySlider.getMin() == 30
                    && opacitySlider.getMax() == 100);
            ViewGroup controls = (ViewGroup) field(overlay, "tools");
            ViewGroup toolbar = (ViewGroup) controls.getChildAt(0);
            check(
                "Compact controls keep three rows and usable button targets",
                controls.getChildCount() == 3
                    && toolbar.getChildAt(0).getWidth()
                        >= Math.round(44 * target.getResources().getDisplayMetrics().density) - 1);
            call(overlay, "toggleEditor", new Class<?>[0]);
            check(
                "Collapse starts at the expanded geometry without a jump",
                layout().height == expandedHeight[0]
                    && field(overlay, "geometryAnimation") != null);
          });
      SystemClock.sleep(80);
      main(
          () -> {
            check(
                "Collapse contains visible intermediate height and opacity",
                layout().height < expandedHeight[0]
                    && field(overlay, "geometryAnimation") != null
                    && ((View) field(overlay, "tools")).getAlpha() < 1);
            int currentHeight = layout().height;
            call(overlay, "toggleEditor", new Class<?>[0]);
            check(
                "Reopening a closing panel continues from its current frame",
                layout().height == currentHeight);
          });
      SystemClock.sleep(380);
      idle();
      main(
          () -> {
            call(overlay, "toggleEditor", new Class<?>[0]);
          });
      SystemClock.sleep(380);
      idle();
      main(
          () -> {
            check(
                "Collapsed controls release animation and keep the lyric window",
                field(overlay, "geometryAnimation") == null
                    && panel() == original
                    && ((View) field(overlay, "tools")).getVisibility() == View.GONE);
            call(overlay, "toggleEditor", new Class<?>[0]);
          });
      SystemClock.sleep(380);
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
      minimumOpacityAndNotification(line);
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
          .remove("lyricsStatusHideLandscape")
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
            android.util.DisplayMetrics display = new android.util.DisplayMetrics();
            target
                .getSystemService(WindowManager.class)
                .getDefaultDisplay()
                .getRealMetrics(display);
            check(
                "Status lyrics stay centered on the physical screen, including cutout displays",
                Math.abs(point[0] * 2 + view.getWidth() - display.widthPixels) <= 1);
            check(
                "Status lyrics center vertically within the status bar",
                Math.abs(point[1] * 2 + view.getHeight() - bar) <= 1);
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
      for (int rotation : new int[] {UiAutomation.ROTATION_FREEZE_90, UiAutomation.ROTATION_FREEZE_270}) {
        getUiAutomation().setRotation(rotation);
        SystemClock.sleep(800);
        idle();
        main(() -> {
          check("Landscape shows status lyrics by default", field(status, "panel") != null);
          View view = (View) field(status, "panel");
          int[] point = new int[2];
          view.getLocationOnScreen(point);
          android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
          target.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
          check("Landscape status lyrics remain centered on the rotated physical screen",
              metrics.widthPixels > metrics.heightPixels && view.isAttachedToWindow()
                  && Math.abs(point[0] * 2 + view.getWidth() - metrics.widthPixels) <= 1);
          saved.edit().putBoolean("lyricsStatusHideLandscape", true).apply();
          call(status, "onDisplayChanged", new Class<?>[0]);
          check("Explicit landscape hiding removes the status lyric window", field(status, "panel") == null);
          saved.edit().putBoolean("lyricsStatusHideLandscape", false).apply();
          call(status, "show", new Class<?>[] {JSONObject.class, JSONObject.class, long.class,
              boolean.class, float.class, String.class}, line, null, 1000L, true, 1f, "");
        });
        idle();
        screenshot("status-landscape-" + rotation + ".png");
      }
      getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);
      SystemClock.sleep(800);
      idle();
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
                      && restored.getString("highlightColor").equals("#35baff")
                      && restored.getString("textColor").equals("#c5ebff")
                      && !restored.getBoolean("locked")
                      && !saved.contains("lyricsLandscapeX")
                      && saved.getBoolean("lyricsStatusEnabled", false));
              saved
                  .edit()
                  .putString("lyricsHighlightColor", "#00d9e9")
                  .putString("lyricsTextColor", "#8fffad")
                  .apply();
              JSONObject migrated = (JSONObject) method.invoke(null, target, new JSONObject());
              check(
                  "The previous default palette upgrades to classic light blue",
                  migrated.getString("highlightColor").equals("#35baff")
                      && migrated.getString("textColor").equals("#c5ebff"));
              saved
                  .edit()
                  .putString("lyricsHighlightColor", "#123abc")
                  .putString("lyricsTextColor", "#abcdef")
                  .apply();
              JSONObject custom = (JSONObject) method.invoke(null, target, new JSONObject());
              check(
                  "Palette upgrade preserves user-picked colors",
                  custom.getString("highlightColor").equals("#123abc")
                      && custom.getString("textColor").equals("#abcdef"));
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
