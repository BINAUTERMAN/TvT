package cn.ting.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import org.json.JSONObject;

/** 绘制和短时帧回调都在原生层，暂停、灭屏和移除窗口时停止。 */
final class LyricsOverlayView extends View {
  private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
  private final String mode, align;
  private final int sung, unsung, secondary, outline;
  private final boolean gradient, smooth, translation;
  private final float textSize, secondaryScale, spacing, shadow;
  private Row current, upcoming;
  private JSONObject source, nextSource;
  private int index;
  private long basePosition, baseClock;
  private boolean playing, scheduled;
  private float speed = 1;
  private float renderScale = 1;
  private final Runnable frame =
      () -> {
        scheduled = false;
        invalidate();
      };

  LyricsOverlayView(Context context, SharedPreferences saved) {
    this(context, saved, false);
  }

  LyricsOverlayView(Context context, SharedPreferences saved, boolean statusBar) {
    super(context);
    float density = getResources().getDisplayMetrics().density;
    textSize =
        saved.getInt(statusBar ? "lyricsStatusSize" : "lyricsSize", statusBar ? 12 : 20)
            * getResources().getDisplayMetrics().scaledDensity;
    secondaryScale = saved.getInt("lyricsSecondarySize", 70) / 100f;
    spacing = saved.getInt("lyricsLineSpacing", 5) * density;
    shadow = saved.getInt("lyricsShadow", 1) * density;
    mode = statusBar ? "single" : saved.getString("lyricsMode", "next");
    align = statusBar ? "center" : saved.getString("lyricsAlign", "center");
    sung =
        readColor(
            saved,
            statusBar ? "lyricsStatusSungColor" : "lyricsHighlightColor",
            statusBar ? "#00d9e9" : FloatingLyrics.accentColor(saved));
    unsung =
        readColor(
            saved,
            statusBar ? "lyricsStatusColor" : "lyricsTextColor",
            statusBar ? "#ffffff" : "#8fffad");
    secondary = readColor(saved, "lyricsSecondaryColor", "#dedee5");
    outline = statusBar ? 0 : saved.getInt("lyricsOutline", 1);
    gradient = !statusBar && saved.getBoolean("lyricsGradient", true);
    smooth = saved.getBoolean("lyricsSmooth", true) && ValueAnimator.areAnimatorsEnabled();
    translation = saved.getBoolean("lyricsTranslation", true);
    paint.setTypeface(
        Typeface.create(
            Typeface.DEFAULT,
            !statusBar && saved.getBoolean("lyricsBold", true) ? Typeface.BOLD : Typeface.NORMAL));
    int padding = statusBar ? 0 : Math.round(5 * density);
    setPadding(0, padding, 0, padding);
    setContentDescription("桌面歌词，轻点展开控制，拖动调整位置");
  }

  private static int readColor(SharedPreferences saved, String key, String fallback) {
    try {
      return Color.parseColor(saved.getString(key, fallback));
    } catch (IllegalArgumentException invalid) {
      return Color.parseColor(fallback);
    }
  }

  void showText(String value) {
    stop();
    boolean hadSecond = upcoming != null;
    source = null;
    nextSource = null;
    if (current == null || !current.timeline.text.equals(value)) {
      JSONObject line = new JSONObject();
      try {
        line.put("text", value);
      } catch (Exception ignored) {
      }
      current = new Row(line, textSize);
      requestLayout();
    }
    upcoming = null;
    if (hadSecond) requestLayout();
    setContentDescription(value);
    invalidate();
  }

  void showLine(
      JSONObject line, JSONObject next, int lineIndex, long position, boolean running, float rate) {
    if (source != line || nextSource != next) {
      source = line;
      nextSource = next;
      current = new Row(line, textSize);
      String meaning = translation ? line.optString("translation") : "";
      if (mode.equals("translation") && !meaning.isEmpty()) {
        JSONObject translated = new JSONObject();
        try {
          translated.put("text", meaning).put("time", line.optDouble("time"));
        } catch (Exception ignored) {
        }
        upcoming = new Row(translated, textSize * secondaryScale);
      } else
        upcoming =
            next == null
                ? null
                : new Row(next, textSize * (mode.equals("translation") ? secondaryScale : 1));
      requestLayout();
      setContentDescription(
          current.timeline.text + (upcoming == null ? "" : "，" + upcoming.timeline.text));
    }
    index = lineIndex;
    basePosition = position;
    baseClock = SystemClock.uptimeMillis();
    playing = running;
    speed = rate;
    cancelFrame();
    invalidate();
  }

  private long position() {
    long position =
        basePosition + (playing ? Math.round((SystemClock.uptimeMillis() - baseClock) * speed) : 0);
    // 跨句由播放器提交新数据，帧内插值不能越过下一句。
    return nextSource == null
        ? position
        : Math.min(position, Math.round(nextSource.optDouble("time") * 1000));
  }

  void stop() {
    basePosition = position();
    playing = false;
    cancelFrame();
  }

  private void cancelFrame() {
    removeCallbacks(frame);
    scheduled = false;
  }

  @Override
  protected void onDetachedFromWindow() {
    stop();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility != VISIBLE) cancelFrame();
    else invalidate();
  }

  @Override
  protected void onVisibilityChanged(View changed, int visibility) {
    super.onVisibilityChanged(changed, visibility);
    if (visibility != VISIBLE) cancelFrame();
    else invalidate();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    float mainHeight = current == null ? textSize * 1.3f : current.layout.getHeight();
    float secondHeight =
        mode.equals("single")
            ? 0
            : (upcoming == null ? mainHeight : upcoming.layout.getHeight()) + spacing;
    float desired = mainHeight + secondHeight;
    int padding = getPaddingTop() + getPaddingBottom();
    renderScale =
        MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
            ? 1
            : Math.min(
                1, Math.max(0, MeasureSpec.getSize(heightSpec) - padding) / Math.max(1, desired));
    setMeasuredDimension(
        MeasureSpec.getSize(widthSpec),
        resolveSize(Math.round(desired * renderScale) + padding, heightSpec));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (current == null || renderScale <= 0) return;
    long position = position();
    int saved = canvas.save();
    canvas.translate(0, getPaddingTop());
    canvas.scale(renderScale, renderScale);
    float top = 0;
    float viewport = getWidth() / renderScale;
    boolean alternating = mode.equals("next");
    boolean bottom = alternating && index % 2 != 0;
    float rowHeight = current.layout.getHeight() + spacing;
    drawRow(canvas, current, top + (bottom ? rowHeight : 0), position, true, viewport);
    if (!mode.equals("single") && upcoming != null)
      drawRow(canvas, upcoming, top + (bottom ? 0 : rowHeight), position, false, viewport);
    canvas.restoreToCount(saved);
    if (playing
        && smooth
        && source != null
        && isAttachedToWindow()
        && isShown()
        && getWindowVisibility() == VISIBLE) {
      long next = current.timeline.nextChange(position);
      boolean moving = current.timeline.moving(position) || current.scrolling(position, viewport);
      if (!scheduled && (moving || next < Long.MAX_VALUE)) {
        scheduled = true;
        postOnAnimationDelayed(
            frame, moving ? 0 : Math.max(1, Math.round((next - position) / speed)));
      }
    }
  }

  private void drawRow(
      Canvas canvas, Row row, float top, long position, boolean active, float viewport) {
    float x =
        row.width > viewport
            ? -row.scroll(position, viewport)
            : align.equals("left")
                ? 0
                : align.equals("right") ? viewport - row.width : (viewport - row.width) / 2;
    int save = canvas.save();
    canvas.clipRect(0, top - shadow, viewport, top + row.layout.getHeight() + shadow);
    canvas.translate(x, top);
    TextPaint pen = row.layout.getPaint();
    pen.setShader(null);
    pen.setColor(Color.argb(190, 20, 20, 24));
    pen.setStyle(Paint.Style.STROKE);
    pen.setStrokeWidth(outline * getResources().getDisplayMetrics().density);
    if (shadow > 0) pen.setShadowLayer(shadow, 0, 1, Color.BLACK);
    if (outline > 0) row.layout.draw(canvas);
    pen.setStyle(Paint.Style.FILL);
    pen.clearShadowLayer();
    int base = !active && mode.equals("translation") ? secondary : unsung;
    setFill(pen, base, row.layout.getHeight());
    row.layout.draw(canvas);
    if (active && source != null) {
      float fill = row.fill(position);
      canvas.clipRect(0, 0, fill, row.layout.getHeight());
      setFill(pen, sung, row.layout.getHeight());
      row.layout.draw(canvas);
    }
    pen.setShader(null);
    canvas.restoreToCount(save);
  }

  private void setFill(TextPaint pen, int color, int height) {
    pen.setColor(color);
    pen.setShader(
        gradient
            ? new LinearGradient(
                0, 0, 0, height, new int[] {lighten(color), color}, null, Shader.TileMode.CLAMP)
            : null);
  }

  private int lighten(int color) {
    return Color.rgb(
        (Color.red(color) + 255) / 2,
        (Color.green(color) + 255) / 2,
        (Color.blue(color) + 255) / 2);
  }

  private final class Row {
    final LyricsTimeline timeline;
    final StaticLayout layout;
    final float width;
    final float[] starts, ends;

    Row(JSONObject line, float size) {
      timeline = new LyricsTimeline(line);
      TextPaint pen = new TextPaint(paint);
      pen.setTextSize(size);
      width = pen.measureText(timeline.text);
      layout =
          StaticLayout.Builder.obtain(
                  timeline.text,
                  0,
                  timeline.text.length(),
                  pen,
                  Math.max(1, (int) Math.ceil(width) + 2))
              .setIncludePad(false)
              .setMaxLines(1)
              .setAlignment(Layout.Alignment.ALIGN_NORMAL)
              .build();
      starts = new float[timeline.words.length];
      ends = new float[timeline.words.length];
      for (int i = 0; i < starts.length; i++) {
        starts[i] = layout.getPrimaryHorizontal(timeline.words[i].start);
        ends[i] = layout.getPrimaryHorizontal(timeline.words[i].end);
      }
    }

    float fill(long position) {
      if (timeline.words.length == 0) return position >= timeline.start ? width : 0;
      if (position >= timeline.end) return width;
      float completed = 0;
      for (int i = 0; i < timeline.words.length; i++) {
        LyricsTimeline.Word word = timeline.words[i];
        if (position >= word.to) completed = ends[i];
        else if (position >= word.from)
          return starts[i] + (ends[i] - starts[i]) * word.fraction(position);
        else break;
      }
      return Math.max(0, completed);
    }

    int scrollWord(float viewport) {
      if (width <= viewport) return -1;
      for (int i = 0; i < ends.length; i++) if (ends[i] > viewport * .75f) return i;
      return -1;
    }

    long scrollDuration(int word) {
      long last = timeline.words[timeline.words.length - 1].from;
      return Math.max(1, Math.round((last - timeline.words[word].from) * .3f));
    }

    float scroll(long position, float viewport) {
      int word = scrollWord(viewport);
      return word < 0
          ? 0
          : LyricsTimeline.scroll(
              width, viewport, position, timeline.words[word].from, scrollDuration(word));
    }

    boolean scrolling(long position, float viewport) {
      int word = scrollWord(viewport);
      return word >= 0
          && position >= timeline.words[word].from
          && position < timeline.words[word].from + scrollDuration(word);
    }
  }
}
