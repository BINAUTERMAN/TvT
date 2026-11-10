package cn.ting.music;

import android.app.DownloadManager;
import android.content.*;

/** 系统下载完成时做一次本地校验，不启动常驻服务或轮询。 */
public final class CacheReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
    if (id < 0) return;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                new MusicCache(context).completed(id);
              } catch (Exception ignored) {
              } finally {
                pending.finish();
              }
            },
            "music-cache-complete")
        .start();
  }
}
