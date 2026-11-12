package cn.ting.music;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.FrameLayout;
import org.json.JSONObject;

/** 独立的小号单行窗口，复用播放器时序。 */
final class StatusBarLyrics {
  private final Context context;
  private final WindowManager windows;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable endPreview = () -> hide();
  private FrameLayout panel;
  private LyricsOverlayView text;
  private WindowManager.LayoutParams layout;
  private boolean previewing;
  private final Runnable displayRefresh = () -> place();

  StatusBarLyrics(Context source) {
    context = LyricsScreen.windowContext(source);
    windows = context.getSystemService(WindowManager.class);
  }

  private SharedPreferences saved() {
    return context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
  }

  boolean enabled() {
    return saved().getBoolean("lyricsStatusEnabled", false) && Settings.canDrawOverlays(context);
  }

  static JSONObject settings(Context context, JSONObject updates) throws Exception {
    SharedPreferences saved = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    SharedPreferences.Editor editor = saved.edit();
    if (updates.optBoolean("resetDefaults"))
      for (String key :
          new String[] {
            "Size", "Width", "Horizontal", "Vertical", "Side", "Color", "SungColor", "HideLandscape"
          }) editor.remove("lyricsStatus" + key);
    if (updates.has("enabled"))
      editor.putBoolean("lyricsStatusEnabled", updates.getBoolean("enabled"));
    String[] keys = {"size", "width", "horizontal", "vertical"};
    int[] low = {8, 15, 0, -12}, high = {24, 80, 100, 48};
    for (int i = 0; i < keys.length; i++)
      if (updates.has(keys[i]))
        editor.putInt(
            "lyricsStatus" + capital(keys[i]),
            Math.max(low[i], Math.min(high[i], updates.getInt(keys[i]))));
    if (updates.has("hideLandscape"))
      editor.putBoolean("lyricsStatusHideLandscape", updates.getBoolean("hideLandscape"));
    if (updates.has("side")) {
      String side = updates.getString("side");
      if (!java.util.Arrays.asList("auto", "left", "right").contains(side))
        throw new IllegalArgumentException("不支持的状态栏位置");
      editor.putString("lyricsStatusSide", side);
    }
    for (String key : new String[] {"color", "sungColor"})
      if (updates.has(key)) {
        String color = updates.getString(key);
        if (!color.matches("#[0-9a-fA-F]{6}"))
          throw new IllegalArgumentException("颜色需为 #RRGGBB 格式");
        editor.putString("lyricsStatus" + capital(key), color);
      }
    editor.apply();
    return new JSONObject()
        .put("enabled", saved.getBoolean("lyricsStatusEnabled", false))
        .put("permission", Settings.canDrawOverlays(context))
        .put("size", saved.getInt("lyricsStatusSize", 12))
        .put("width", saved.getInt("lyricsStatusWidth", 38))
        .put("horizontal", saved.getInt("lyricsStatusHorizontal", 50))
        .put("vertical", saved.getInt("lyricsStatusVertical", 0))
        .put("side", saved.getString("lyricsStatusSide", "auto"))
        .put("hideLandscape", saved.getBoolean("lyricsStatusHideLandscape", false))
        .put("color", saved.getString("lyricsStatusColor", "#ffffff"))
        .put("sungColor", saved.getString("lyricsStatusSungColor", "#00d9e9"))
        .put("error", saved.getString("lyricsStatusError", ""));
  }

  private static String capital(String key) {
    return Character.toUpperCase(key.charAt(0)) + key.substring(1);
  }

  void show(
      JSONObject line,
      JSONObject next,
      long position,
      boolean playing,
      float speed,
      String fallback) {
    if (previewing) return;
    if (!enabled() || !playing || !interactive() || hiddenInLandscape()) {
      hide();
      return;
    }
    ensurePanel();
    if (text == null) return;
    if (line == null) text.showText(fallback);
    else text.showLine(line, next, 0, position, true, speed);
    place();
  }

  void preview() {
    hide();
    if (!Settings.canDrawOverlays(context) || !interactive()) return;
    previewing = true;
    ensurePanel();
    if (text != null) {
      text.showText("TvT 状态栏歌词预览");
      place();
    }
    handler.postDelayed(endPreview, 5000);
  }

  void refresh() {
    boolean wasPreview = previewing;
    hide();
    if (wasPreview) preview();
  }

  boolean isPreviewing() {
    return previewing;
  }

  private boolean interactive() {
    return context.getSystemService(PowerManager.class).isInteractive();
  }

  private boolean hiddenInLandscape() {
    Rect full = LyricsScreen.full(windows);
    return saved().getBoolean("lyricsStatusHideLandscape", false) && full.width() > full.height();
  }

  private void ensurePanel() {
    if (panel != null) return;
    panel =
        new FrameLayout(context) {
          @Override
          protected void onConfigurationChanged(android.content.res.Configuration configuration) {
            super.onConfigurationChanged(configuration);
            onDisplayChanged();
          }
        };
    text = new LyricsOverlayView(context, saved(), true);
    panel.addView(
        text,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    int flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    layout =
        new WindowManager.LayoutParams(
            dp(132),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.LEFT;
    layout.alpha = .78f;
    if (Build.VERSION.SDK_INT >= 31)
      layout.alpha =
          Math.min(
              layout.alpha,
              context
                  .getSystemService(android.hardware.input.InputManager.class)
                  .getMaximumObscuringOpacityForTouch());
    if (Build.VERSION.SDK_INT >= 30) {
      layout.setFitInsetsTypes(0);
      layout.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    } else if (Build.VERSION.SDK_INT >= 28)
      layout.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    panel.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          onDisplayChanged();
          return insets;
        });
    panel.addOnLayoutChangeListener(
        (view, l, t, r, b, ol, ot, or, ob) -> {
          if (r - l != or - ol || b - t != ob - ot) place();
        });
    place();
    try {
      windows.addView(panel, layout);
      saved().edit().remove("lyricsStatusError").apply();
    } catch (RuntimeException unavailable) {
      panel = null;
      text = null;
      saved().edit().putString("lyricsStatusError", "系统未允许显示状态栏歌词，请检查悬浮窗权限后重试。").apply();
    }
  }

  private void place() {
    if (panel == null || layout == null) return;
    Rect full = LyricsScreen.full(windows);
    String side = saved().getString("lyricsStatusSide", "auto");
    boolean centered = side.equals("auto");
    int left = Math.min(dp(56), full.width() / 4),
        right = Math.max(left + 1, full.width() - dp(72));
    int splitLeft = -1, splitRight = -1;
    int bar = Math.max(1, LyricsScreen.statusBarHeight(context, windows));
    if (!centered && Build.VERSION.SDK_INT >= 29) {
      DisplayCutout cutout = windows.getDefaultDisplay().getCutout();
      if (cutout != null)
        for (Rect rect : cutout.getBoundingRects()) {
          if (rect.top < bar && rect.bottom > 0) {
            if (rect.left <= left) left = Math.max(left, rect.right + dp(56));
            else if (rect.right >= right) right = Math.min(right, rect.left - dp(72));
            else {
              splitLeft = rect.left - dp(4);
              splitRight = rect.right + dp(4);
            }
          }
        }
    }
    if (splitLeft >= 0) {
      boolean takeLeft = side.equals("left");
      if (takeLeft) right = splitLeft;
      else left = splitRight;
    } else if (!side.equals("auto")) {
      int middle = (left + right) / 2;
      if (side.equals("left")) right = middle;
      else left = middle;
    }
    if (centered) {
      left = 0;
      right = full.width();
    }
    left = Math.min(left, full.width() - 1);
    right = Math.max(left + 1, Math.min(right, full.width()));
    int width =
        Math.max(
            1,
            Math.min(
                right - left,
                Math.round(full.width() * saved().getInt("lyricsStatusWidth", 38) / 100f)));
    int x =
        left
            + LyricsGeometry.restore(
                saved().getInt("lyricsStatusHorizontal", 50) / 100f, right - left - width);
    int height = Math.max(1, text.getMeasuredHeight());
    int y = Math.max(0, (bar - height) / 2 + dp(saved().getInt("lyricsStatusVertical", 0)));
    if (layout.width == width && layout.x == x && layout.y == y) return;
    layout.width = width;
    layout.x = x;
    layout.y = y;
    if (panel.isAttachedToWindow())
      try {
        windows.updateViewLayout(panel, layout);
      } catch (RuntimeException unavailable) {
        hide();
      }
  }

  void onDisplayChanged() {
    if (!previewing && hiddenInLandscape()) {
      hide();
      return;
    }
    if (panel != null) {
      panel.removeCallbacks(displayRefresh);
      panel.postOnAnimation(displayRefresh);
    }
  }

  void hide() {
    handler.removeCallbacks(endPreview);
    if (panel != null) panel.removeCallbacks(displayRefresh);
    previewing = false;
    if (text != null) text.stop();
    if (panel != null)
      try {
        windows.removeView(panel);
      } catch (IllegalArgumentException ignored) {
      }
    panel = null;
    text = null;
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
