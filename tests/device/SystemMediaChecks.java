package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.KeyEvent;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** 真机解析外部事件、接收系统下载回调，并渲染不同屏幕配置下的媒体图标。 */
public final class SystemMediaChecks extends Instrumentation {
  private int checks;

  private void report(String text) {
    Bundle result = new Bundle();
    result.putString("stream", text + "\n");
    sendStatus(0, result);
  }

  private void check(String name, boolean passed) {
    report(name + ": " + (passed ? "PASS" : "FAIL"));
    if (!passed) throw new AssertionError(name);
    checks++;
  }

  private Method method(Class<?> type, String name, Class<?>... args) throws Exception {
    Method value = type.getDeclaredMethod(name, args);
    value.setAccessible(true);
    return value;
  }

  private Object field(Class<?> type, String name) throws Exception {
    Field value = type.getDeclaredField(name);
    value.setAccessible(true);
    return value.get(null);
  }

  private void inputChecks(Context target) throws Exception {
    Class<?> type = target.getClassLoader().loadClass("cn.ting.music.MediaButtonReceiver");
    Method parse = method(type, "event", Intent.class),
        receive = type.getMethod("onReceive", Context.class, Intent.class);
    Object receiver = type.getConstructor().newInstance();
    for (Intent intent :
        new Intent[] {
          null,
          new Intent("unrelated"),
          new Intent(Intent.ACTION_MEDIA_BUTTON),
          new Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, new Bundle()),
          new Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, "invalid")
        }) {
      check("Malformed media payload is ignored", parse.invoke(null, intent) == null);
      receive.invoke(receiver, target, intent);
    }
    Intent unreadable =
        new Intent(Intent.ACTION_MEDIA_BUTTON) {
          @Override
          public <T extends Parcelable> T getParcelableExtra(String key) {
            throw new BadParcelableException("controlled invalid parcel");
          }
        };
    check("Controlled parcel decoding failure is ignored", parse.invoke(null, unreadable) == null);
    receive.invoke(receiver, target, unreadable);
    for (int key :
        new int[] {
          KeyEvent.KEYCODE_MEDIA_PLAY,
          KeyEvent.KEYCODE_MEDIA_PAUSE,
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
          KeyEvent.KEYCODE_MEDIA_NEXT,
          KeyEvent.KEYCODE_MEDIA_PREVIOUS,
          KeyEvent.KEYCODE_HEADSETHOOK
        }) {
      KeyEvent event =
          (KeyEvent)
              parse.invoke(
                  null,
                  new Intent(Intent.ACTION_MEDIA_BUTTON)
                      .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, key)));
      check("Valid media key remains readable " + key, event != null && event.getKeyCode() == key);
    }
    PackageManager manager = target.getPackageManager();
    ActivityInfo cache =
        manager.getReceiverInfo(
            new ComponentName(target.getPackageName(), "cn.ting.music.CacheReceiver"), 0);
    check(
        "Download receiver requires the system sender permission",
        cache.exported
            && "android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS".equals(cache.permission));
    check(
        "System download provider holds that permission",
        manager.checkPermission(cache.permission, "com.android.providers.downloads")
            == PackageManager.PERMISSION_GRANTED);
    check(
        "Ordinary test app does not hold that permission",
        manager.checkPermission(cache.permission, getContext().getPackageName())
            == PackageManager.PERMISSION_DENIED);
    Class<?> cacheType = target.getClassLoader().loadClass("cn.ting.music.CacheReceiver");
    Object cacheReceiver = cacheType.getConstructor().newInstance();
    cacheType
        .getMethod("onReceive", Context.class, Intent.class)
        .invoke(cacheReceiver, target, null);
    cacheType
        .getMethod("onReceive", Context.class, Intent.class)
        .invoke(
            cacheReceiver,
            target,
            new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L));
    check(
        "Invalid download events leave no pending work",
        ((Set<?>) field(cacheType, "pendingIds")).isEmpty());
  }

  private int[] ink(Drawable drawable, int size) {
    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    drawable.setBounds(0, 0, size, size);
    drawable.draw(new Canvas(bitmap));
    int left = size, top = size, right = -1, bottom = -1;
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++)
        if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
          left = Math.min(left, x);
          top = Math.min(top, y);
          right = Math.max(right, x);
          bottom = Math.max(bottom, y);
        }
    bitmap.recycle();
    return new int[] {left, top, right, bottom};
  }

  private Context screen(Context target, int width, int density, float fontScale) {
    Configuration config = new Configuration(target.getResources().getConfiguration());
    config.smallestScreenWidthDp = width;
    config.screenWidthDp = width;
    config.screenHeightDp = width * 2;
    config.densityDpi = density;
    config.fontScale = fontScale;
    return target.createConfigurationContext(config);
  }

  private void icons(Context target) throws Exception {
    String[] names = {
      "ic_heart", "ic_heart_filled", "ic_lyrics", "ic_lyrics_active", "ic_lyrics_unlock"
    };
    int renders = 0;
    for (int density : new int[] {160, 320, 480})
      for (float font : new float[] {1, 1.5f})
        for (int size : new int[] {24, 48, 72})
          for (String name : names) {
            Context phone = screen(target, 360, density, font),
                tablet = screen(target, 800, density, font);
            int id = target.getResources().getIdentifier(name, "drawable", target.getPackageName());
            Drawable phoneIcon = phone.getDrawable(id), tabletIcon = tablet.getDrawable(id);
            if (tabletIcon.getIntrinsicWidth() < phoneIcon.getIntrinsicWidth() * 1.9)
              throw new AssertionError("Large icon source resolution");
            int[] normal = ink(phoneIcon, size), large = ink(tabletIcon, size);
            int normalWidth = normal[2] - normal[0] + 1, largeWidth = large[2] - large[0] + 1;
            if (largeWidth <= 0
                || largeWidth > normalWidth * .72 + 1
                || large[0] < 0
                || large[2] >= size
                || Math.abs((large[0] + large[2]) / 2.0 - (size - 1) / 2.0) > size * .12)
              throw new AssertionError("Icon geometry " + name + " size=" + size);
            renders++;
          }
    check(
        "Phone/tablet icon geometry is stable across DPI, font scale and host bounds",
        renders == 90);
    Bitmap preview = Bitmap.createBitmap(640, 200, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(preview);
    canvas.drawColor(0xff252525);
    Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    text.setColor(Color.WHITE);
    text.setTextSize(18);
    String[] all = {
      "ic_previous",
      "ic_pause",
      "ic_next",
      "ic_heart",
      "ic_heart_filled",
      "ic_lyrics",
      "ic_lyrics_active",
      "ic_lyrics_unlock"
    };
    for (int row = 0; row < 2; row++) {
      Context context = screen(target, row == 0 ? 360 : 800, 320, 1);
      canvas.drawText(row == 0 ? "Phone" : "Tablet", 8, row * 100 + 22, text);
      for (int i = 0; i < all.length; i++) {
        Drawable drawable =
            context.getDrawable(
                target.getResources().getIdentifier(all[i], "drawable", target.getPackageName()));
        drawable.setBounds(i * 80 + 16, row * 100 + 36, i * 80 + 64, row * 100 + 84);
        drawable.draw(canvas);
      }
    }
    File file = new File(target.getExternalFilesDir("Pictures"), "media-icons-check.png");
    file.getParentFile().mkdirs();
    try (OutputStream output = new FileOutputStream(file)) {
      preview.compress(Bitmap.CompressFormat.PNG, 100, output);
    }
    preview.recycle();
    report("ICON_PREVIEW_READY");
  }

  private void completion(Context target) throws Exception {
    ClassLoader loader = target.getClassLoader();
    Class<?> apiType = loader.loadClass("cn.ting.music.KugouApi"),
        cacheType = loader.loadClass("cn.ting.music.MusicCache");
    Object api = apiType.getConstructor(Context.class).newInstance(target);
    Constructor<?> constructor = cacheType.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    Object cache = constructor.newInstance(target);
    Method playback = method(cacheType, "playback", JSONObject.class),
        start = method(cacheType, "start", apiType, JSONObject.class, String.class),
        remove = method(cacheType, "remove", String.class);
    JSONArray songs =
        ((JSONObject) apiType.getMethod("search", String.class, int.class).invoke(api, "纯音乐 铃声", 1))
            .getJSONObject("data")
            .getJSONArray("lists");
    JSONObject sample = null;
    for (int i = 0; i < songs.length(); i++) {
      JSONObject song = songs.getJSONObject(i);
      if (song.optInt("Duration") < 3
          || song.optInt("Duration") > 90
          || song.optLong("FileSize") > 2000000
          || !song.optString("FileHash").matches("[a-fA-F0-9]{32}")) continue;
      JSONObject track =
          new JSONObject()
              .put("hash", song.getString("FileHash").toLowerCase(Locale.ROOT))
              .put("title", song.optString("SongName"))
              .put("albumId", song.optString("AlbumID"))
              .put("audioId", song.optString("MixSongID"));
      if (playback.invoke(cache, track) == null) {
        sample = track;
        break;
      }
    }
    check("Fresh small online download sample is available", sample != null);
    final JSONObject track = sample;
    CountDownLatch completed = new CountDownLatch(1);
    BroadcastReceiver changed =
        new BroadcastReceiver() {
          public void onReceive(Context context, Intent intent) {
            try {
              if (playback.invoke(cache, track) != null) completed.countDown();
            } catch (Exception ignored) {
            }
          }
        };
    IntentFilter filter = new IntentFilter("cn.ting.music.CACHE_CHANGED");
    if (Build.VERSION.SDK_INT >= 33)
      target.registerReceiver(changed, filter, Context.RECEIVER_NOT_EXPORTED);
    else target.registerReceiver(changed, filter);
    try {
      start.invoke(cache, api, track, "128");
      check(
          "Real DownloadManager completion reaches permission-protected receiver",
          completed.await(40, TimeUnit.SECONDS));
      check(
          "System callback marks owned cache ready without list polling",
          playback.invoke(cache, track) != null);
    } finally {
      target.unregisterReceiver(changed);
      remove.invoke(cache, track.getString("hash"));
    }
  }

  private void boundedWork(Context target) throws Exception {
    Class<?> type = target.getClassLoader().loadClass("cn.ting.music.CacheReceiver");
    ThreadPoolExecutor worker = (ThreadPoolExecutor) field(type, "worker");
    Set<?> ids = (Set<?>) field(type, "pendingIds");
    for (int i = 0; i < 30 && (!ids.isEmpty() || worker.getActiveCount() != 0); i++)
      SystemClock.sleep(100);
    check(
        "Completion worker is idle before controlled burst",
        ids.isEmpty() && worker.getActiveCount() == 0);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    worker.execute(
        () -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        });
    entered.await(2, TimeUnit.SECONDS);
    Object receiver = type.getConstructor().newInstance();
    Method receive = type.getMethod("onReceive", Context.class, Intent.class);
    try {
      for (int i = 0; i < 64; i++)
        receive.invoke(
            receiver,
            target,
            new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, Long.MAX_VALUE - i));
      int count = worker.getQueue().size();
      for (int i = 0; i < 20; i++)
        receive.invoke(
            receiver,
            target,
            new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, Long.MAX_VALUE));
      check(
          "Burst is bounded and duplicate completion is coalesced",
          worker.getMaximumPoolSize() == 1
              && worker.getQueue().size() <= 16
              && worker.getQueue().size() == count
              && ids.size() <= 16);
    } finally {
      release.countDown();
    }
    for (int i = 0; i < 30 && (!ids.isEmpty() || worker.getActiveCount() != 0); i++)
      SystemClock.sleep(100);
    check(
        "Rejected and completed work releases deduplication records",
        ids.isEmpty() && worker.getQueue().isEmpty());
    SystemClock.sleep(5500);
    check("Idle completion worker retires", worker.getPoolSize() == 0);
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Context target = getTargetContext();
      inputChecks(target);
      icons(target);
      completion(target);
      boundedWork(target);
      result.putString("stream", "SYSTEM_MEDIA_PASS checks=" + checks + "\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      Throwable cause = error;
      while (cause.getCause() != null) cause = cause.getCause();
      for (StackTraceElement entry : cause.getStackTrace())
        if (entry.getClassName().startsWith("cn.ting.music")) report(entry.toString());
      result.putString("stream", "SYSTEM_MEDIA_FAIL " + cause + "\n");
    }
    finish(code, result);
  }
}
