package cn.ting.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;

final class LyricsControls extends LinearLayout {
  interface Actions {
    void update(JSONObject changes);

    void playback(String action);

    void close();
  }

  private final Actions actions;
  private final SharedPreferences saved;
  private TextView play;

  LyricsControls(Context context, SharedPreferences saved, Actions actions) {
    super(context);
    this.actions = actions;
    this.saved = saved;
    setOrientation(VERTICAL);
    LinearLayout transport = row();
    button(transport, "锁定", "锁定歌词", () -> change("locked", true));
    button(transport, "|◁", "上一首", () -> actions.playback("previous"));
    play = button(transport, "Ⅱ", "播放或暂停", () -> actions.playback("toggle"));
    button(transport, "▷|", "下一首", () -> actions.playback("next"));
    button(transport, "×", "关闭桌面歌词", actions::close);
    LinearLayout colors = row();
    preset(colors, "青碧", "#00d9e9", "#8fffad");
    preset(colors, "暖金", "#ffe4ac", "#ffffff");
    preset(colors, "樱粉", "#ffacd3", "#dedee5");
    LinearLayout typography = row();
    button(typography, "A−", "缩小歌词", () -> change("size", saved.getInt("lyricsSize", 20) - 2));
    button(typography, "A+", "放大歌词", () -> change("size", saved.getInt("lyricsSize", 20) + 2));
    button(
        typography,
        "单双行",
        "切换单行双行",
        () ->
            change(
                "mode",
                saved.getString("lyricsMode", "next").equals("single") ? "next" : "single"));
    LinearLayout opacity = row();
    TextView label = new TextView(context);
    label.setText("不透明度");
    label.setTextColor(Color.WHITE);
    label.setTextSize(12);
    opacity.addView(label);
    SeekBar slider = new SeekBar(context);
    slider.setContentDescription("桌面歌词不透明度");
    slider.setMax(70);
    slider.setProgress(saved.getInt("lyricsOpacity", 100) - 30);
    opacity.addView(slider, new LayoutParams(0, dp(44), 1));
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {}

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {
            change("opacity", bar.getProgress() + 30);
          }
        });
  }

  void setPlaying(boolean playing) {
    play.setText(playing ? "Ⅱ" : "▷");
  }

  private void preset(LinearLayout row, String name, String sung, String unsung) {
    TextView button =
        button(
            row,
            name,
            name + "歌词配色",
            () -> {
              try {
                actions.update(
                    new JSONObject().put("highlightColor", sung).put("textColor", unsung));
              } catch (Exception invalid) {
                throw new IllegalStateException(invalid);
              }
            });
    button.setTextColor(Color.parseColor(sung));
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
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    return row;
  }

  private TextView button(LinearLayout row, String label, String description, Runnable action) {
    TextView button = new TextView(getContext());
    button.setText(label);
    button.setTextColor(Color.WHITE);
    button.setTextSize(14);
    button.setGravity(Gravity.CENTER);
    button.setContentDescription(description);
    row.addView(button, new LayoutParams(0, dp(44), 1));
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
