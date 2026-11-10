package cn.ting.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public final class MediaButtonReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
    KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
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
    context.startForegroundService(new Intent(context, PlaybackService.class).setAction(action));
  }
}
