package cn.ting.music;

import android.app.DownloadManager;
import android.content.*;
import java.util.Set;
import java.util.concurrent.*;

/** 系统下载完成时做一次本地校验，不启动常驻服务或轮询。 */
public final class CacheReceiver extends BroadcastReceiver {
  private static final Set<Long> pendingIds = ConcurrentHashMap.newKeySet();
  private static final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          0,
          1,
          5,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          task -> {
            Thread thread = new Thread(task, "music-cache-complete");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          });

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))
      return;
    final long id;
    try {
      id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
    } catch (RuntimeException malformed) {
      return;
    }
    if (id <= 0 || !pendingIds.add(id)) return;
    PendingResult pending = goAsync();
    Context application = context.getApplicationContext();
    try {
      worker.execute(
          () -> {
            try {
              new MusicCache(application).completed(id);
            } catch (Exception ignored) {
            } finally {
              pendingIds.remove(id);
              if (pending != null) pending.finish();
            }
          });
    } catch (RejectedExecutionException busy) {
      pendingIds.remove(id);
      if (pending != null) pending.finish();
      // 完成记录仍由系统保存，队列繁忙时在下次读取缓存列表时核对。
    }
  }
}
