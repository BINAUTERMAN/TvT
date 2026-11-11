package cn.ting.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** 歌词按时间点更新，锁定后不拦截桌面触摸。 */
final class FloatingLyrics {
  private final Context context;
  private final WindowManager windows;
  private LinearLayout panel;
  private LyricsOverlayView text;
  private LyricsControls tools;
  private boolean editing;
  private JSONObject lastLine;
  private JSONObject lastNext;
  private long lastPosition;
  private int lastIndex;
  private boolean lastPlaying;
  private float lastSpeed = 1;
  private WindowManager.LayoutParams layout;
  private String lastLyric = "";
  private Rect bounds;
  private float relativeX, relativeY;
  private String positionKey;
  private boolean dragging;
  private ValueAnimator snapAnimation;

  FloatingLyrics(Context context) {
    this.context =
        Build.VERSION.SDK_INT >= 30
            ? context
                .createDisplayContext(
                    context
                        .getSystemService(android.hardware.display.DisplayManager.class)
                        .getDisplay(Display.DEFAULT_DISPLAY))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            : context;
    windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
  }

  private SharedPreferences preferences() {
    return context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
  }

  boolean enabled() {
    return preferences().getBoolean("floatingLyrics", false) && Settings.canDrawOverlays(context);
  }

  boolean locked() {
    return preferences().getBoolean("lyricsLocked", false);
  }

  static JSONObject settings(Context context, JSONObject updates) throws Exception {
    SharedPreferences saved = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    SharedPreferences.Editor editor = saved.edit();
    if (updates.has("size"))
      editor.putInt("lyricsSize", Math.max(12, Math.min(48, updates.optInt("size", 20))));
    if (updates.has("locked")) editor.putBoolean("lyricsLocked", updates.optBoolean("locked"));
    if (updates.has("width"))
      editor.putInt("lyricsWidth", Math.max(30, Math.min(100, updates.optInt("width", 88))));
    if (updates.has("accent")
        && java.util.Arrays.asList("white", "gold", "mint", "cyan")
            .contains(updates.optString("accent"))) {
      editor.putString("lyricsAccent", updates.optString("accent"));
      editor.remove("lyricsHighlightColor");
    }
    for (String key :
        new String[] {"highlightColor", "textColor", "secondaryColor", "backgroundColor"}) {
      if (updates.has(key)) {
        String color = updates.optString(key);
        if (!color.matches("#[0-9a-fA-F]{6}"))
          throw new IllegalArgumentException("颜色需为 #RRGGBB 格式");
        editor.putString("lyrics" + capitalized(key), color);
      }
    }
    String[] numericKeys = {
      "opacity",
      "backgroundOpacity",
      "lineSpacing",
      "secondarySize",
      "shadow",
      "snapDistance",
      "outline"
    };
    int[] minimums = {30, 0, 0, 50, 0, 8, 0}, maximums = {100, 90, 20, 100, 6, 64, 4};
    for (int index = 0; index < numericKeys.length; index++) {
      String key = numericKeys[index];
      if (updates.has(key))
        editor.putInt(
            "lyrics" + capitalized(key),
            Math.max(minimums[index], Math.min(maximums[index], updates.optInt(key))));
    }
    for (String key : new String[] {"bold", "snap", "gradient", "smooth"})
      if (updates.has(key)) editor.putBoolean("lyrics" + capitalized(key), updates.optBoolean(key));
    if (updates.has("align")) {
      String value = updates.optString("align");
      if (!java.util.Arrays.asList("left", "center", "right").contains(value))
        throw new IllegalArgumentException("不支持的歌词对齐方式");
      editor.putString("lyricsAlign", value);
    }
    if (updates.has("mode")) {
      String value = updates.optString("mode");
      if (!java.util.Arrays.asList("single", "translation", "next").contains(value))
        throw new IllegalArgumentException("不支持的歌词显示模式");
      editor.putString("lyricsMode", value);
    }
    if (updates.optBoolean("resetPosition"))
      editor
          .remove("lyricsPortraitX")
          .remove("lyricsPortraitY")
          .remove("lyricsLandscapeX")
          .remove("lyricsLandscapeY")
          .remove("lyricsRelativeX")
          .remove("lyricsRelativeY")
          .remove("lyricsX")
          .remove("lyricsY");
    editor.apply();
    return new JSONObject()
        .put("size", saved.getInt("lyricsSize", 20))
        .put("width", saved.getInt("lyricsWidth", 88))
        .put("accent", saved.getString("lyricsAccent", "cyan"))
        .put("highlightColor", saved.getString("lyricsHighlightColor", accentColor(saved)))
        .put("textColor", saved.getString("lyricsTextColor", "#8fffad"))
        .put("secondaryColor", saved.getString("lyricsSecondaryColor", "#dedee5"))
        .put("backgroundColor", saved.getString("lyricsBackgroundColor", "#1c1c20"))
        .put("opacity", saved.getInt("lyricsOpacity", 100))
        .put("backgroundOpacity", saved.getInt("lyricsBackgroundOpacity", 0))
        .put("lineSpacing", saved.getInt("lyricsLineSpacing", 5))
        .put("secondarySize", saved.getInt("lyricsSecondarySize", 70))
        .put("shadow", saved.getInt("lyricsShadow", 1))
        .put("outline", saved.getInt("lyricsOutline", 1))
        .put("gradient", saved.getBoolean("lyricsGradient", true))
        .put("smooth", saved.getBoolean("lyricsSmooth", true))
        .put("snapDistance", saved.getInt("lyricsSnapDistance", 24))
        .put("snap", saved.getBoolean("lyricsSnap", true))
        .put("bold", saved.getBoolean("lyricsBold", true))
        .put("align", saved.getString("lyricsAlign", "center"))
        .put("mode", saved.getString("lyricsMode", "next"))
        .put("locked", saved.getBoolean("lyricsLocked", false))
        .put("enabled", saved.getBoolean("floatingLyrics", false))
        .put("permission", Settings.canDrawOverlays(context));
  }

  private static String capitalized(String key) {
    return Character.toUpperCase(key.charAt(0)) + key.substring(1);
  }

  static String accentColor(SharedPreferences saved) {
    String value = saved.getString("lyricsAccent", "cyan");
    return value.equals("mint")
        ? "#c0f4dc"
        : value.equals("white") ? "#ffffff" : value.equals("gold") ? "#ffe4ac" : "#00d9e9";
  }

  void refresh() {
    if (PlaybackService.instance != null) {
      PlaybackService.instance.refreshFloatingLyrics();
      return;
    }
    hide();
    if (enabled()) {
      if (lastLine == null) show(lastLyric);
      else showLine(lastLine, lastNext, lastIndex, lastPosition, lastPlaying, lastSpeed);
    }
  }

  void show(String lyric) {
    lastLine = null;
    lastLyric = lyric;
    if (!enabled()) {
      hide();
      return;
    }
    if (panel == null) createPanel();
    if (text != null) text.showText(lyric);
  }

  void showLine(
      JSONObject line, JSONObject next, int index, long position, boolean playing, float speed) {
    lastLine = line;
    lastNext = next;
    lastPosition = position;
    lastIndex = index;
    lastPlaying = playing;
    lastSpeed = speed;
    if (!enabled()) {
      hide();
      return;
    }
    if (panel == null) createPanel();
    if (text == null) return;
    text.showLine(line, next, index, position, playing, speed);
    if (tools != null) tools.setPlaying(playing);
  }

  void stopAnimation() {
    lastPlaying = false;
    if (text != null) text.stop();
  }

  private int color(String key, String fallback) {
    try {
      return Color.parseColor(preferences().getString("lyrics" + capitalized(key), fallback));
    } catch (IllegalArgumentException invalid) {
      return Color.parseColor(fallback);
    }
  }

  private int dp(float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private void createPanel() {
    tools = null;
    panel =
        new LinearLayout(context) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = bounds == null ? MeasureSpec.getSize(heightSpec) : bounds.height();
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
            super.onMeasure(
                widthSpec, MeasureSpec.makeMeasureSpec(Math.max(0, limit), MeasureSpec.AT_MOST));
          }
        };
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    boolean locked = locked();
    {
      GradientDrawable background = new GradientDrawable();
      int alpha =
          Math.round(
              (editing && !locked ? 75 : preferences().getInt("lyricsBackgroundOpacity", 0))
                  * 255
                  / 100f);
      background.setColor((color("backgroundColor", "#1c1c20") & 0xffffff) | (alpha << 24));
      background.setCornerRadius(dp(18));
      panel.setBackground(background);
      panel.setPadding(dp(12), dp(8), dp(12), dp(8));
    }
    if (!locked) {
      tools =
          new LyricsControls(
              context,
              preferences(),
              new LyricsControls.Actions() {
                public void update(JSONObject changes) {
                  try {
                    settings(context, changes);
                    refresh();
                  } catch (Exception error) {
                    Toast.makeText(context, error.getMessage(), Toast.LENGTH_SHORT).show();
                  }
                }

                public void playback(String action) {
                  if (PlaybackService.instance == null) return;
                  try {
                    PlaybackService.instance.execute(action, new JSONObject());
                  } catch (Exception error) {
                    Toast.makeText(context, error.getMessage(), Toast.LENGTH_SHORT).show();
                  }
                }

                public void close() {
                  preferences().edit().putBoolean("floatingLyrics", false).apply();
                  if (PlaybackService.instance != null)
                    PlaybackService.instance.refreshFloatingLyrics();
                  else hide();
                }
              });
      tools.setVisibility(editing ? View.VISIBLE : View.GONE);
    }
    text = new LyricsOverlayView(context, preferences());
    // 横屏或大字体时优先保留控制按钮，歌词在剩余高度内等比缩放。
    panel.addView(
        text,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    if (tools != null) panel.addView(tools);
    int flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    if (locked) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    bounds = screenBounds();
    if (Build.VERSION.SDK_INT < 30) {
      panel.setOnApplyWindowInsetsListener(
          (view, insets) -> {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            int left = insets.getStableInsetLeft(), top = insets.getStableInsetTop();
            int right = insets.getStableInsetRight(), bottom = insets.getStableInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
              DisplayCutout cutout = insets.getDisplayCutout();
              left = Math.max(left, cutout.getSafeInsetLeft());
              top = Math.max(top, cutout.getSafeInsetTop());
              right = Math.max(right, cutout.getSafeInsetRight());
              bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
            bounds.set(left, top, metrics.widthPixels - right, metrics.heightPixels - bottom);
            layout.width =
                Math.round(bounds.width() * preferences().getInt("lyricsWidth", 88) / 100f);
            restorePosition();
            return insets;
          });
    }
    positionKey = bounds.width() > bounds.height() ? "lyricsLandscape" : "lyricsPortrait";
    relativeX =
        preferences().getFloat(positionKey + "X", preferences().getFloat("lyricsRelativeX", .5f));
    relativeY =
        preferences().getFloat(positionKey + "Y", preferences().getFloat("lyricsRelativeY", .3f));
    layout =
        new WindowManager.LayoutParams(
            windowWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.LEFT;
    if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0);
    if (Build.VERSION.SDK_INT >= 28)
      layout.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    layout.alpha = preferences().getInt("lyricsOpacity", 100) / 100f;
    if (locked && android.os.Build.VERSION.SDK_INT >= 31) {
      float maximum =
          context
              .getSystemService(android.hardware.input.InputManager.class)
              .getMaximumObscuringOpacityForTouch();
      layout.alpha = Math.min(layout.alpha, Math.min(.78f, maximum));
    }
    layout.x = bounds.left + LyricsGeometry.restore(relativeX, bounds.width() - layout.width);
    layout.y = bounds.top + LyricsGeometry.restore(relativeY, bounds.height());
    panel.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (!dragging
              && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
            restorePosition();
        });
    if (!locked)
      text.setOnTouchListener(
          new View.OnTouchListener() {
            float startX, startY;
            int originX, originY;
            boolean moved;

            public boolean onTouch(View view, MotionEvent event) {
              if (event.getAction() == MotionEvent.ACTION_DOWN) {
                cancelSnap();
                startX = event.getRawX();
                startY = event.getRawY();
                originX = layout.x;
                originY = layout.y;
                moved = false;
                dragging = true;
                return true;
              }
              if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getRawX() - startX) + Math.abs(event.getRawY() - startY) > dp(6))
                  moved = true;
                if (!moved) return true;
                layout.x =
                    bounds.left
                        + LyricsGeometry.clamp(
                            originX - bounds.left + (int) (event.getRawX() - startX),
                            bounds.width() - panel.getWidth());
                layout.y =
                    bounds.top
                        + LyricsGeometry.clamp(
                            originY - bounds.top + (int) (event.getRawY() - startY),
                            bounds.height() - panel.getHeight());
                updateLayout();
                return true;
              }
              if (event.getAction() == MotionEvent.ACTION_UP
                  || event.getAction() == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                if (!moved && event.getAction() == MotionEvent.ACTION_UP) {
                  toggleEditor();
                  view.performClick();
                  return true;
                }
                if (moved) savePosition(event.getAction() == MotionEvent.ACTION_UP);
                return true;
              }
              return false;
            }
          });
    try {
      windows.addView(panel, layout);
    } catch (RuntimeException unavailable) {
      panel = null;
      text = null;
      preferences().edit().putBoolean("floatingLyrics", false).apply();
    }
  }

  private void toggleEditor() {
    editing = !editing;
    tools.animate().cancel();
    if (editing) {
      tools.setVisibility(View.VISIBLE);
      tools.setAlpha(0);
      tools.animate().alpha(1).setDuration(motionEnabled() ? 120 : 0).start();
    } else {
      LyricsControls currentTools = tools;
      tools
          .animate()
          .alpha(0)
          .setDuration(motionEnabled() ? 120 : 0)
          .withEndAction(
              () -> {
                if (tools == currentTools && !editing) tools.setVisibility(View.GONE);
              })
          .start();
    }
    GradientDrawable background = (GradientDrawable) panel.getBackground();
    int alpha =
        Math.round(
            (editing ? 75 : preferences().getInt("lyricsBackgroundOpacity", 0)) * 255 / 100f);
    background.setColor((color("backgroundColor", "#1c1c20") & 0xffffff) | (alpha << 24));
    layout.width = windowWidth();
    updateLayout();
  }

  private int windowWidth() {
    int preferred = Math.round(bounds.width() * preferences().getInt("lyricsWidth", 88) / 100f);
    return Math.min(
        bounds.width(), editing && !locked() ? Math.max(dp(264), preferred) : preferred);
  }

  private Rect screenBounds() {
    if (Build.VERSION.SDK_INT >= 30) {
      WindowMetrics metrics = windows.getCurrentWindowMetrics();
      Rect area = new Rect(metrics.getBounds());
      android.graphics.Insets insets =
          metrics
              .getWindowInsets()
              .getInsetsIgnoringVisibility(
                  WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      area.inset(insets.left, insets.top, insets.right, insets.bottom);
      return area;
    }
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    windows.getDefaultDisplay().getMetrics(metrics);
    int top = 0;
    int resource = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
    if (resource != 0) top = context.getResources().getDimensionPixelSize(resource);
    return new Rect(0, top, metrics.widthPixels, metrics.heightPixels);
  }

  private void restorePosition() {
    if (panel == null) return;
    layout.x = bounds.left + LyricsGeometry.restore(relativeX, bounds.width() - panel.getWidth());
    layout.y = bounds.top + LyricsGeometry.restore(relativeY, bounds.height() - panel.getHeight());
    updateLayout();
  }

  private void savePosition(boolean snap) {
    cancelSnap();
    int startX = layout.x, startY = layout.y;
    int maxX = Math.max(0, bounds.width() - panel.getWidth());
    int maxY = Math.max(0, bounds.height() - panel.getHeight());
    if (snap && preferences().getBoolean("lyricsSnap", true)) {
      int distance = dp(preferences().getInt("lyricsSnapDistance", 24));
      layout.x = bounds.left + LyricsGeometry.snap(layout.x - bounds.left, maxX, distance);
      layout.y = bounds.top + LyricsGeometry.snap(layout.y - bounds.top, maxY, distance);
    }
    relativeX = LyricsGeometry.relative(layout.x - bounds.left, maxX);
    relativeY = LyricsGeometry.relative(layout.y - bounds.top, maxY);
    preferences()
        .edit()
        .putFloat(positionKey + "X", relativeX)
        .putFloat(positionKey + "Y", relativeY)
        .apply();
    int targetX = layout.x, targetY = layout.y;
    if (snap && motionEnabled() && (startX != targetX || startY != targetY)) {
      layout.x = startX;
      layout.y = startY;
      ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
      snapAnimation = animator;
      animator.setDuration(180);
      animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
      animator.addUpdateListener(
          value -> {
            float fraction = (Float) value.getAnimatedValue();
            layout.x = Math.round(startX + (targetX - startX) * fraction);
            layout.y = Math.round(startY + (targetY - startY) * fraction);
            updateLayout();
          });
      animator.addListener(
          new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator value) {
              if (snapAnimation == value) snapAnimation = null;
            }
          });
      animator.start();
    } else updateLayout();
  }

  private boolean motionEnabled() {
    return preferences().getBoolean("lyricsSmooth", true) && ValueAnimator.areAnimatorsEnabled();
  }

  private void cancelSnap() {
    if (snapAnimation != null) {
      snapAnimation.cancel();
      snapAnimation = null;
    }
  }

  private void updateLayout() {
    if (panel == null || !panel.isAttachedToWindow()) return;
    try {
      windows.updateViewLayout(panel, layout);
    } catch (RuntimeException unavailable) {
      hide();
    }
  }

  void hide() {
    cancelSnap();
    dragging = false;
    if (text != null) text.stop();
    if (tools != null) tools.animate().cancel();
    if (panel != null) {
      try {
        windows.removeView(panel);
      } catch (IllegalArgumentException ignored) {
      }
      panel = null;
      text = null;
      tools = null;
    }
  }
}
