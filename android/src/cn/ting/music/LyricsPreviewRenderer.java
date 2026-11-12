package cn.ting.music;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.json.*;

final class LyricsPreviewRenderer {
  static JSONObject render(Context context, JSONObject updates) throws Exception {
    MemoryPreferences preview = new MemoryPreferences(context.getSharedPreferences("preferences", Context.MODE_PRIVATE));
    Context isolated = new ContextWrapper(context) {
      @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preview; }
    };
    // 复用参数校验与原生绘制，不写入真实配置或创建悬浮窗口。
    FloatingLyrics.settings(isolated, updates);
    float density = context.getResources().getDisplayMetrics().density;
    Rect screen = LyricsScreen.safe(context, context.getSystemService(WindowManager.class), null);
    int width = Math.max(1, Math.round(screen.width() * preview.getInt("lyricsWidth", 88) / 100f));
    int horizontal = Math.round(12 * density), vertical = Math.round(8 * density);
    LyricsOverlayView text = new LyricsOverlayView(context, preview);
    String words = "让音乐在此刻流动";
    JSONArray timing = new JSONArray();
    for (int index = 0; index < words.length(); index++)
      timing.put(new JSONObject().put("text", words.substring(index, index + 1)).put("time", index * 1000).put("end", (index + 1) * 1000));
    JSONObject line = new JSONObject().put("text", words).put("translation", "Let the music flow in this moment")
        .put("time", 0).put("end", 12000).put("words", timing);
    JSONObject next = new JSONObject().put("text", "下一句，陪你走过每个日常").put("time", 12).put("end", 24000);
    text.showLine(line, next, 0, 3000, false, 1);
    text.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width - horizontal * 2), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(Math.max(1, screen.height() - vertical * 2), View.MeasureSpec.AT_MOST));
    text.layout(0, 0, text.getMeasuredWidth(), text.getMeasuredHeight());
    int height = text.getMeasuredHeight() + vertical * 2;
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(bitmap);
      float alpha = preview.getInt("lyricsOpacity", 100) / 100f;
      if (preview.getBoolean("lyricsLocked", false) && Build.VERSION.SDK_INT >= 31)
        alpha = Math.min(alpha, Math.min(.78f, context.getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch()));
      canvas.saveLayerAlpha(0, 0, width, height, Math.round(alpha * 255));
      GradientDrawable background = new GradientDrawable();
      int color = Color.parseColor(preview.getString("lyricsBackgroundColor", "#1c1c20"));
      int backgroundAlpha = Math.round(preview.getInt("lyricsBackgroundOpacity", 0) * 255 / 100f);
      background.setColor((color & 0xffffff) | (backgroundAlpha << 24));
      background.setCornerRadius(18 * density);
      background.setBounds(0, 0, width, height);
      background.draw(canvas);
      canvas.translate(horizontal, vertical);
      text.draw(canvas);
      canvas.restore();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
      return new JSONObject().put("image", "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
          .put("width", width).put("height", height);
    } finally {
      text.stop();
      bitmap.recycle();
    }
  }

  private static final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    MemoryPreferences(SharedPreferences source) { values.putAll(source.getAll()); }
    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public boolean contains(String key) { return values.containsKey(key); }
    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
    public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public Editor edit() {
      return new Editor() {
        private final Map<String, Object> changes = new HashMap<>();
        private boolean cleared;
        public Editor putString(String key, String value) { changes.put(key, value); return this; }
        public Editor putStringSet(String key, Set<String> value) { changes.put(key, value); return this; }
        public Editor putInt(String key, int value) { changes.put(key, value); return this; }
        public Editor putLong(String key, long value) { changes.put(key, value); return this; }
        public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
        public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
        public Editor remove(String key) { changes.put(key, null); return this; }
        public Editor clear() { cleared = true; return this; }
        public boolean commit() { apply(); return true; }
        public void apply() {
          if (cleared) values.clear();
          for (Map.Entry<String, Object> entry : changes.entrySet()) {
            if (entry.getValue() == null) values.remove(entry.getKey());
            else values.put(entry.getKey(), entry.getValue());
          }
        }
      };
    }
  }
}
