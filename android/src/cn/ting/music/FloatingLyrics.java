package cn.ting.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** 歌词按时间点更新，锁定后透明且不拦截桌面触摸。 */
final class FloatingLyrics {
  private final Context context;
  private final WindowManager windows;
  private LinearLayout panel;
  private TextView text;
  private LinearLayout tools;
  private boolean editing;
  private JSONObject lastLine;
  private String lastNext = "";
  private long lastPosition;
  private String rendered = "";
  private WindowManager.LayoutParams layout;
  private String lastLyric = "";

  FloatingLyrics(Context context) {
    this.context = context;
    windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
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
      editor.putInt("lyricsSize", Math.max(12, Math.min(42, updates.optInt("size", 20))));
    if (updates.has("locked")) editor.putBoolean("lyricsLocked", updates.optBoolean("locked"));
    if (updates.has("width"))
      editor.putInt("lyricsWidth", Math.max(45, Math.min(100, updates.optInt("width", 88))));
    if (updates.has("accent")
        && java.util.Arrays.asList("white", "gold", "mint").contains(updates.optString("accent")))
      editor.putString("lyricsAccent", updates.optString("accent"));
    if (updates.optBoolean("resetPosition"))
      editor
          .remove("lyricsRelativeX")
          .remove("lyricsRelativeY")
          .remove("lyricsX")
          .remove("lyricsY");
    editor.apply();
    return new JSONObject()
        .put("size", saved.getInt("lyricsSize", 20))
        .put("width", saved.getInt("lyricsWidth", 88))
        .put("accent", saved.getString("lyricsAccent", "gold"))
        .put("locked", saved.getBoolean("lyricsLocked", false))
        .put("enabled", saved.getBoolean("floatingLyrics", false))
        .put("permission", Settings.canDrawOverlays(context));
  }

  void refresh() {
    hide();
    if (enabled()) {
      if (lastLine == null) show(lastLyric);
      else showLine(lastLine, lastNext, lastPosition);
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
    if (text != null && !text.getText().toString().equals(lyric)) text.setText(lyric);
  }

  void showLine(JSONObject line, String next, long position) {
    lastLine = line;
    lastNext = next;
    lastPosition = position;
    if (!enabled()) {
      hide();
      return;
    }
    if (panel == null) createPanel();
    if (text == null) return;
    String original = line.optString("text");
    String meaning =
        preferences().getBoolean("lyricsTranslation", true) ? line.optString("translation") : "";
    String secondary = !meaning.isEmpty() ? meaning : next;
    AirLyricsProgress progress = AirLyricsProgress.at(line, position + 1);
    String key =
        original + "\n" + secondary + ":" + progress.completedEnd + ":" + progress.activeEnd;
    if (key.equals(rendered)) return;
    rendered = key;
    SpannableStringBuilder content = new SpannableStringBuilder(original);
    int accent = accent();
    int end = Math.min(original.length(), Math.max(progress.completedEnd, progress.activeEnd));
    if (end > 0)
      content.setSpan(new ForegroundColorSpan(accent), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (!secondary.isEmpty()) {
      int start = content.length();
      content.append("\n").append(secondary);
      content.setSpan(
          new RelativeSizeSpan(.7f), start, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      content.setSpan(
          new ForegroundColorSpan(0xffdedee5),
          start,
          content.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    text.setText(content);
  }

  private int accent() {
    String value = preferences().getString("lyricsAccent", "gold");
    return value.equals("mint") ? 0xffc0f4dc : value.equals("white") ? Color.WHITE : 0xffffe4ac;
  }

  private int dp(float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private void createPanel() {
    rendered = "";
    panel = new LinearLayout(context);
    panel.setOrientation(LinearLayout.VERTICAL);
    boolean locked = locked();
    if (!locked) {
      GradientDrawable background = new GradientDrawable();
      background.setColor(0xe61c1c20);
      background.setStroke(dp(1), 0x24ffffff);
      background.setCornerRadius(dp(26));
      panel.setBackground(background);
      panel.setPadding(dp(18), dp(12), dp(18), dp(14));
      panel.setElevation(dp(6));
    } else {
      panel.setPadding(dp(10), dp(8), dp(10), dp(8));
    }
    if (!locked) {
      tools = new LinearLayout(context);
      tools.setGravity(Gravity.CENTER_VERTICAL);
      tools.setVisibility(editing ? View.VISIBLE : View.GONE);
      addTool(tools, "A−", "缩小歌词", () -> resize(-2));
      addTool(tools, "A+", "放大歌词", () -> resize(2));
      addTool(
          tools,
          "锁定",
          "锁定歌词",
          () -> {
            preferences().edit().putBoolean("lyricsLocked", true).apply();
            refresh();
          });
      addTool(
          tools,
          "×",
          "关闭歌词",
          () -> {
            preferences().edit().putBoolean("floatingLyrics", false).apply();
            hide();
          });
      panel.addView(tools);
    }
    text = new TextView(context);
    text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
    text.setTextSize(preferences().getInt("lyricsSize", 20));
    text.setTextColor(Color.WHITE);
    text.setShadowLayer(dp(3), 0, dp(1), 0xf0000000);
    text.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
    text.setMaxLines(4);
    text.setMinLines(2);
    text.setLineSpacing(dp(5), 1);
    text.setIncludeFontPadding(false);
    text.setPadding(0, dp(3), 0, dp(3));
    text.setEllipsize(android.text.TextUtils.TruncateAt.END);
    panel.addView(text);
    int flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    if (locked) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    layout =
        new WindowManager.LayoutParams(
            Math.min(
                dp(680),
                Math.round(
                    context.getResources().getDisplayMetrics().widthPixels
                        * preferences().getInt("lyricsWidth", 88)
                        / 100f)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.LEFT;
    if (locked && android.os.Build.VERSION.SDK_INT >= 31) {
      float maximum =
          context
              .getSystemService(android.hardware.input.InputManager.class)
              .getMaximumObscuringOpacityForTouch();
      layout.alpha = Math.min(.78f, maximum);
    }
    layout.x = preferences().getInt("lyricsX", dp(24));
    layout.y = preferences().getInt("lyricsY", dp(220));
    if (!locked)
      text.setOnTouchListener(
          new View.OnTouchListener() {
            float startX, startY;
            int originX, originY;
            boolean moved;

            public boolean onTouch(View view, MotionEvent event) {
              if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startX = event.getRawX();
                startY = event.getRawY();
                originX = layout.x;
                originY = layout.y;
                moved = false;
                return true;
              }
              if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getRawX() - startX) + Math.abs(event.getRawY() - startY) > dp(6))
                  moved = true;
                if (!moved) return true;
                layout.x =
                    clamp(
                        originX + (int) (event.getRawX() - startX),
                        context.getResources().getDisplayMetrics().widthPixels - panel.getWidth());
                layout.y =
                    clamp(
                        originY + (int) (event.getRawY() - startY),
                        context.getResources().getDisplayMetrics().heightPixels
                            - panel.getHeight());
                windows.updateViewLayout(panel, layout);
                return true;
              }
              if (event.getAction() == MotionEvent.ACTION_UP
                  || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (!moved && event.getAction() == MotionEvent.ACTION_UP) {
                  editing = !editing;
                  tools.setVisibility(editing ? View.VISIBLE : View.GONE);
                  view.performClick();
                  return true;
                }
                preferences()
                    .edit()
                    .putInt("lyricsX", layout.x)
                    .putInt("lyricsY", layout.y)
                    .putFloat(
                        "lyricsRelativeX",
                        (float) layout.x
                            / Math.max(
                                1,
                                context.getResources().getDisplayMetrics().widthPixels
                                    - panel.getWidth()))
                    .putFloat(
                        "lyricsRelativeY",
                        (float) layout.y
                            / Math.max(
                                1,
                                context.getResources().getDisplayMetrics().heightPixels
                                    - panel.getHeight()))
                    .apply();
                return true;
              }
              return false;
            }
          });
    try {
      windows.addView(panel, layout);
      panel.post(
          () -> {
            if (panel != null) {
              if (preferences().contains("lyricsRelativeX"))
                layout.x =
                    AirLyricsProgress.coordinate(
                        preferences().getFloat("lyricsRelativeX", .5f),
                        context.getResources().getDisplayMetrics().widthPixels,
                        panel.getWidth());
              if (preferences().contains("lyricsRelativeY"))
                layout.y =
                    AirLyricsProgress.coordinate(
                        preferences().getFloat("lyricsRelativeY", .3f),
                        context.getResources().getDisplayMetrics().heightPixels,
                        panel.getHeight());
              layout.x =
                  clamp(
                      layout.x,
                      context.getResources().getDisplayMetrics().widthPixels - panel.getWidth());
              layout.y =
                  clamp(
                      layout.y,
                      context.getResources().getDisplayMetrics().heightPixels - panel.getHeight());
              windows.updateViewLayout(panel, layout);
            }
          });
    } catch (RuntimeException unavailable) {
      panel = null;
      text = null;
      rendered = "";
      preferences().edit().putBoolean("floatingLyrics", false).apply();
    }
  }

  private int clamp(int value, int maximum) {
    return Math.max(0, Math.min(Math.max(0, maximum), value));
  }

  private void addTool(LinearLayout tools, String label, String description, Runnable action) {
    TextView button = new TextView(context);
    button.setText(label);
    button.setContentDescription(description);
    button.setTextColor(0xffeeeeef);
    button.setTextSize(13);
    button.setGravity(Gravity.CENTER);
    tools.addView(button, new LinearLayout.LayoutParams(dp(48), dp(44)));
    button.setOnClickListener(view -> action.run());
  }

  private void resize(int change) {
    preferences()
        .edit()
        .putInt(
            "lyricsSize",
            Math.max(12, Math.min(42, preferences().getInt("lyricsSize", 20) + change)))
        .apply();
    refresh();
  }

  void hide() {
    if (panel != null) {
      try {
        windows.removeView(panel);
      } catch (IllegalArgumentException ignored) {
      }
      panel = null;
      text = null;
      tools = null;
      rendered = "";
    }
  }
}
