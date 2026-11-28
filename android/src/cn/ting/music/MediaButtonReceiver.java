package cn.ting.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.view.KeyEvent;

public final class MediaButtonReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    KeyEvent event = event(intent);
    if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0)
      return;
    String action;
    switch (event.getKeyCode()) {
      case KeyEvent.KEYCODE_MEDIA_NEXT:
        action = "next";
        break;
      case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
        action = "previous";
        break;
      case KeyEvent.KEYCODE_MEDIA_PLAY:
        action = "resume";
        break;
      case KeyEvent.KEYCODE_MEDIA_PAUSE:
      case KeyEvent.KEYCODE_MEDIA_STOP:
        action = "pause";
        break;
      case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
      case KeyEvent.KEYCODE_HEADSETHOOK:
        action = "toggle";
        break;
      default:
        return;
    }
    if (PlaybackService.instance != null) {
      try {
        PlaybackService.instance.execute(action, new org.json.JSONObject());
      } catch (Exception ignored) {
      }
      return;
    }
    if (action.equals("pause")
        || context
            .getSharedPreferences("playback", Context.MODE_PRIVATE)
            .getString("queue", "[]")
            .equals("[]")) return;
    try {
      context.startForegroundService(new Intent(context, PlaybackService.class).setAction(action));
    } catch (IllegalStateException | SecurityException denied) {
      // 后台启动被系统拒绝时忽略该事件，不能让外部广播导致应用退出。
    }
  }

  @SuppressWarnings("deprecation")
  static KeyEvent event(Intent intent) {
    if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return null;
    try {
      // 保持 Android 8 兼容；先检查实际类型，再做转换。
      Parcelable value = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
      return value instanceof KeyEvent ? (KeyEvent) value : null;
    } catch (RuntimeException malformed) {
      return null;
    }
  }
}
