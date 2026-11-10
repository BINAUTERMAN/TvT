package cn.ting.music;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 封面仅在切歌时加载，限制解码尺寸并复用最近几张图片。 */
final class ArtworkLoader {
  interface Callback {
    void ready(Bitmap bitmap);
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final LruCache<String, Bitmap> cache = new LruCache<>(4);

  void load(String source, Callback callback) {
    if (source.isEmpty()) return;
    String address = source.replace("{size}", "400").replaceFirst("^http:", "https:");
    Bitmap cached = cache.get(address);
    if (cached != null) {
      callback.ready(cached);
      return;
    }
    worker.execute(
        () -> {
          try {
            URL url = new URL(address);
            if (!url.getProtocol().equals("https") || !url.getHost().endsWith(".kugou.com")) return;
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            try (InputStream input = connection.getInputStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
              byte[] buffer = new byte[8192];
              int count;
              while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 2 * 1024 * 1024) return;
                output.write(buffer, 0, count);
              }
              byte[] bytes = output.toByteArray();
              BitmapFactory.Options options = new BitmapFactory.Options();
              options.inJustDecodeBounds = true;
              BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
              options.inSampleSize = 1;
              while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512)
                options.inSampleSize *= 2;
              options.inJustDecodeBounds = false;
              Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
              if (bitmap != null) {
                cache.put(address, bitmap);
                main.post(() -> callback.ready(bitmap));
              }
            } finally {
              connection.disconnect();
            }
          } catch (Exception ignored) {
          }
        });
  }

  void close() {
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
    cache.evictAll();
  }
}
