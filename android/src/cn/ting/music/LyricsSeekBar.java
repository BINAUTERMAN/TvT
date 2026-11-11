package cn.ting.music;

import android.content.Context;
import android.view.*;
import android.widget.SeekBar;

/** 只从滑块开始拖动；点按轨道不修改数值。 */
final class LyricsSeekBar extends SeekBar {
  private OnSeekBarChangeListener listener;
  private float downX;
  private int start, pointer = -1;
  private boolean moved;

  LyricsSeekBar(Context context) {
    super(context);
  }

  @Override
  public void setOnSeekBarChangeListener(OnSeekBarChangeListener value) {
    listener = value;
    super.setOnSeekBarChangeListener(value);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) return false;
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) {
      float ratio = (float) getProgress() / Math.max(1, getMax());
      if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) ratio = 1 - ratio;
      float center = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) * ratio;
      if (Math.abs(event.getX() - center) > 20 * getResources().getDisplayMetrics().density)
        return false;
      pointer = event.getPointerId(0);
      downX = event.getX();
      start = getProgress();
      moved = false;
      return true;
    }
    if (pointer < 0) return false;
    int at = event.findPointerIndex(pointer);
    if (action == MotionEvent.ACTION_MOVE && at >= 0) {
      float delta = event.getX(at) - downX;
      if (!moved && Math.abs(delta) < ViewConfiguration.get(getContext()).getScaledTouchSlop())
        return true;
      if (!moved && listener != null) listener.onStartTrackingTouch(this);
      moved = true;
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
      if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) delta = -delta;
      setProgress(
          start
              + Math.round(
                  delta
                      / Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight())
                      * getMax()));
      return true;
    }
    if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
      pointer = -1;
      if (action == MotionEvent.ACTION_CANCEL) setProgress(start);
      else if (moved && getProgress() != start && listener != null)
        listener.onStopTrackingTouch(this);
      else performClick();
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
      return true;
    }
    return true;
  }

  @Override
  public boolean onKeyUp(int key, KeyEvent event) {
    boolean handled = super.onKeyUp(key, event);
    if (listener != null
        && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT))
      listener.onStopTrackingTouch(this);
    return handled;
  }

  @Override
  public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
    int previous = getProgress();
    boolean handled = super.performAccessibilityAction(action, arguments);
    if (handled && previous != getProgress() && listener != null)
      listener.onStopTrackingTouch(this);
    return handled;
  }
}
