package cn.ting.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** 浮窗只放播放与常用调整，配色等次要操作收进菜单。 */
final class LyricsControls extends LinearLayout {
  interface Actions {
    void update(JSONObject changes);

    void playback(String action);

    void close();

    void collapse();
  }

  private final Actions actions;
  private final SharedPreferences saved;
  private final IconButton play;
  private SeekBar opacitySlider;
  private PopupWindow overflow;

  LyricsControls(Context context, SharedPreferences saved, Actions actions) {
    super(context);
    this.actions = actions;
    this.saved = saved;
    setOrientation(VERTICAL);
    setPadding(dp(8), dp(6), dp(8), dp(6));
    GradientDrawable surface = new GradientDrawable();
    surface.setColor(0xea242429);
    surface.setCornerRadius(dp(18));
    surface.setStroke(dp(1), 0x24ffffff);
    setBackground(surface);
    LinearLayout transport = row();
    button(transport, "lock", "锁定歌词", () -> change("locked", true));
    button(transport, "previous", "上一首", () -> actions.playback("previous"));
    play = button(transport, "pause", "播放或暂停", () -> actions.playback("toggle"));
    button(transport, "next", "下一首", () -> actions.playback("next"));
    IconButton more = button(transport, "more", "更多歌词样式", null);
    more.setOnClickListener(view -> menu(more));
    button(transport, "collapse", "收起歌词控制面板", actions::collapse);
    slider("字号", "桌面歌词字号", "size", 12, 48, 20, "sp");
    slider("透明度", "桌面歌词不透明度", "opacity", 30, 100, 100, "%");
  }

  void setPlaying(boolean playing) {
    play.kind = playing ? "pause" : "play";
    play.invalidate();
  }

  private void menu(View anchor) {
    if (overflow != null) overflow.dismiss();
    LinearLayout content = new LinearLayout(getContext());
    content.setOrientation(VERTICAL);
    content.setPadding(dp(12), dp(12), dp(12), dp(8));
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff242429);
    background.setCornerRadius(dp(20));
    background.setStroke(dp(1), 0x28ffffff);
    content.setBackground(background);
    TextView title = label("显示模式");
    title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    title.setTextColor(0x99ffffff);
    content.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, dp(28)));
    LinearLayout modes = new LinearLayout(getContext());
    content.addView(modes);
    String[] values = {"single", "translation", "next"};
    String[] labels = {"单行", "译文", "双行"};
    for (int index = 0; index < values.length; index++) {
      String mode = values[index];
      TextView option = menuChoice(labels[index], mode.equals(saved.getString("lyricsMode", "next")) ? 0xff35baff : Color.WHITE);
      LayoutParams optionLayout = new LayoutParams(0, dp(44), 1);
      optionLayout.setMargins(dp(3), 0, dp(3), 0);
      modes.addView(option, optionLayout);
      option.setOnClickListener(view -> { overflow.dismiss(); change("mode", mode); });
    }
    LinearLayout palettes = new LinearLayout(getContext());
    LayoutParams paletteLayout = new LayoutParams(LayoutParams.MATCH_PARENT, dp(52));
    paletteLayout.topMargin = dp(8);
    content.addView(palettes, paletteLayout);
    String[] names = {"经典蓝", "暖金", "樱粉"};
    String[] colors = {FloatingLyrics.DEFAULT_HIGHLIGHT, "#ffe4ac", "#ffacd3"};
    String[] textColors = {FloatingLyrics.DEFAULT_TEXT, "#ffffff", "#ffffff"};
    String[] secondaryColors = {FloatingLyrics.DEFAULT_SECONDARY, "#dedee5", "#ead2df"};
    for (int index = 0; index < names.length; index++) {
      String color = colors[index];
      String textColor = textColors[index], secondaryColor = secondaryColors[index];
      TextView option = menuChoice(names[index], Color.parseColor(color));
      LayoutParams optionLayout = new LayoutParams(0, dp(44), 1);
      optionLayout.setMargins(dp(3), 0, dp(3), 0);
      palettes.addView(option, optionLayout);
      option.setOnClickListener(view -> {
        overflow.dismiss();
        try { actions.update(new JSONObject().put("highlightColor", color).put("textColor", textColor).put("secondaryColor", secondaryColor)); }
        catch (Exception invalid) { throw new IllegalStateException(invalid); }
      });
    }
    TextView close = menuChoice("关闭桌面歌词", 0xffff8585);
    content.addView(close, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));
    close.setOnClickListener(view -> { overflow.dismiss(); actions.close(); });
    PopupWindow popup = new PopupWindow(content, dp(264), LayoutParams.WRAP_CONTENT, true);
    overflow = popup;
    popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    popup.setOutsideTouchable(true);
    popup.setElevation(dp(14));
    popup.setAnimationStyle(android.animation.ValueAnimator.areAnimatorsEnabled()
        && saved.getBoolean("lyricsSmooth", true) ? R.style.LyricsPopupAnimation : 0);
    popup.showAsDropDown(anchor, 0, dp(6), Gravity.RIGHT);
  }

  private TextView menuChoice(String name, int color) {
    TextView option = label(name);
    option.setTextSize(13);
    option.setTextColor(color);
    option.setContentDescription(name);
    option.setFocusable(true);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0x0fffffff);
    background.setCornerRadius(dp(12));
    option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), background, null));
    return option;
  }

  void setOpacity(int opacity) {
    opacitySlider.setProgress(opacity);
  }

  @Override
  protected void onDetachedFromWindow() {
    if (overflow != null) overflow.dismiss();
    overflow = null;
    super.onDetachedFromWindow();
  }

  private void slider(
      String title, String description, String key, int low, int high, int fallback, String unit) {
    LinearLayout row = row();
    row.addView(label(title), new LayoutParams(dp(46), dp(44)));
    String preference = "lyrics" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
    TextView value = label(saved.getInt(preference, fallback) + unit);
    value.setTextSize(11);
    value.setTextColor(0xbfffffff);
    SeekBar slider = new LyricsSeekBar(getContext());
    if (key.equals("opacity")) opacitySlider = slider;
    slider.setContentDescription(description);
    slider.setMax(high);
    slider.setMin(low);
    slider.setProgress(saved.getInt(preference, fallback));
    slider.setSplitTrack(false);
    slider.setProgressTintList(ColorStateList.valueOf(0xffe7e7ed));
    slider.setProgressBackgroundTintList(ColorStateList.valueOf(0x38ffffff));
    GradientDrawable thumb = new GradientDrawable();
    thumb.setShape(GradientDrawable.OVAL);
    thumb.setColor(Color.WHITE);
    thumb.setSize(dp(20), dp(20));
    slider.setThumb(thumb);
    slider.setThumbOffset(dp(10));
    slider.setPadding(dp(10), 0, dp(10), 0);
    row.addView(slider, new LayoutParams(0, dp(44), 1));
    row.addView(value, new LayoutParams(dp(42), dp(44)));
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {
            value.setText(progress + unit);
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {
            change(key, bar.getProgress());
          }
        });
  }

  private TextView label(String title) {
    TextView text = new TextView(getContext());
    text.setText(title);
    text.setTextColor(Color.WHITE);
    text.setTextSize(12);
    text.setGravity(Gravity.CENTER);
    return text;
  }

  private void change(String key, Object value) {
    try {
      actions.update(new JSONObject().put(key, value));
    } catch (Exception invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private LinearLayout row() {
    LinearLayout row = new LinearLayout(getContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));
    return row;
  }

  private IconButton button(LinearLayout row, String kind, String description, Runnable action) {
    IconButton button = new IconButton(kind);
    button.setContentDescription(description);
    button.setFocusable(true);
    row.addView(button, new LayoutParams(0, dp(44), 1));
    button.setOnClickListener(
        view -> {
          if (action != null) action.run();
        });
    return button;
  }

  private final class IconButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String kind;

    IconButton(String kind) {
      super(LyricsControls.this.getContext());
      this.kind = kind;
      GradientDrawable shape = new GradientDrawable();
      shape.setColor(0x0affffff);
      shape.setCornerRadius(dp(14));
      setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape, null));
    }

    @Override
    public CharSequence getAccessibilityClassName() {
      return "android.widget.Button";
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int save = canvas.save();
      canvas.translate((getWidth() - dp(22)) / 2f, (getHeight() - dp(22)) / 2f);
      canvas.scale(dp(22) / 22f, dp(22) / 22f);
      paint.setColor(Color.WHITE);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.7f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      if (kind.equals("lock")) {
        canvas.drawRoundRect(5, 10, 17, 19, 2, 2, paint);
        canvas.drawArc(7, 3, 15, 16, 180, 180, false, paint);
      } else if (kind.equals("collapse")) {
        Path p = new Path();
        p.moveTo(5, 8);
        p.lineTo(11, 14);
        p.lineTo(17, 8);
        canvas.drawPath(p, paint);
      } else if (kind.equals("more")) {
        paint.setStyle(Paint.Style.FILL);
        for (int x = 4; x <= 18; x += 7) canvas.drawCircle(x, 11, 1.5f, paint);
      } else if (kind.equals("pause")) {
        paint.setStrokeWidth(3);
        canvas.drawLine(8, 5, 8, 17, paint);
        canvas.drawLine(14, 5, 14, 17, paint);
      } else {
        if (kind.equals("previous")) {
          canvas.translate(22, 0);
          canvas.scale(-1, 1);
        }
        Path p = new Path();
        p.moveTo(6, 5);
        p.lineTo(16, 11);
        p.lineTo(6, 17);
        p.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(p, paint);
        if (!kind.equals("play")) {
          paint.setStyle(Paint.Style.STROKE);
          canvas.drawLine(18, 5, 18, 17, paint);
        }
      }
      canvas.restoreToCount(save);
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
