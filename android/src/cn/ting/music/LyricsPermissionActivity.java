package cn.ting.music;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

/** 通知点击直接进入系统授权，返回后只开启桌面歌词。 */
public final class LyricsPermissionActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (Settings.canDrawOverlays(this)) {
      enable();
      return;
    }
    startActivityForResult(
        new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
        1);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    enable();
  }

  private void enable() {
    if (Settings.canDrawOverlays(this)) {
      getSharedPreferences("preferences", MODE_PRIVATE)
          .edit()
          .putBoolean("floatingLyrics", true)
          .putBoolean("lyricsLocked", false)
          .apply();
      if (PlaybackService.instance != null) PlaybackService.instance.loadFloatingLyrics();
    }
    finish();
  }
}
