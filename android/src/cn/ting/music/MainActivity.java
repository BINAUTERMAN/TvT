package cn.ting.music;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.webkit.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final String ORIGIN = "https://appassets.androidplatform.net/";
  private WebView web;
  private KugouApi api;
  private AppUpdater updates;
  private final BroadcastReceiver starReady =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          if (visible && loaded) javascript("window.dispatchEvent(new Event('ting:star-ready'))");
        }
      };
  private final BroadcastReceiver cacheChanged =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          if (visible) javascript("window.dispatchEvent(new Event('ting:cache-changed'))");
        }
      };
  private final BroadcastReceiver updateDownload =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          if (updates != null
              && updates.ownsDownload(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)))
            javascript("window.dispatchEvent(new Event('ting:update-download'))");
        }
      };
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private boolean visible;
  private boolean loaded;
  private boolean imeVisible;
  private boolean sentInitialState;
  private String pendingExport;
  private boolean serviceBound;
  private final java.util.ArrayDeque<Runnable> playerCommands = new java.util.ArrayDeque<>();
  private final ServiceConnection playerConnection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
          handler.removeCallbacks(playerConnectionTimeout);
          while (!playerCommands.isEmpty()) playerCommands.removeFirst().run();
          handler.removeCallbacks(tick);
          handler.post(tick);
        }

        public void onServiceDisconnected(ComponentName name) {
          handler.removeCallbacks(tick);
        }
      };
  private final Runnable playerConnectionTimeout = () -> failPendingPlayerCommands();

  private void ensurePlayer() {
    if (!serviceBound) {
      startService(new Intent(this, PlaybackService.class));
      serviceBound =
          bindService(
              new Intent(this, PlaybackService.class), playerConnection, Context.BIND_AUTO_CREATE);
    }
  }

  private void failPendingPlayerCommands() {
    while (!playerCommands.isEmpty()) playerCommands.removeFirst().run();
  }

  private void dispatchPlayer(String id, String action, JSONObject data) {
    Runnable command =
        () -> {
          try {
            PlaybackService service = PlaybackService.instance;
            if (service == null) throw new Exception("播放服务恢复失败，请重新打开应用");
            service.execute(action, data);
            resolve(id, true, null);
          } catch (Exception error) {
            resolve(id, null, friendlyError(error));
          }
        };
    if (PlaybackService.instance != null) {
      command.run();
      return;
    }
    playerCommands.addLast(command);
    ensurePlayer();
    handler.removeCallbacks(playerConnectionTimeout);
    handler.postDelayed(playerConnectionTimeout, 8000);
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (!visible) return;
          PlaybackService service = PlaybackService.instance;
          if (service != null && loaded) {
            JSONObject state = service.snapshot();
            sendState(state, sentInitialState);
            sentInitialState = true;
            service.observer =
                changed -> {
                  if (!visible) return;
                  sendState(changed);
                  handler.removeCallbacks(this);
                  if (changed.optBoolean("playing") || changed.optBoolean("loading"))
                    handler.postDelayed(this, 1000);
                };
            if (!state.optBoolean("playing") && !state.optBoolean("loading")) return;
          }
          if (service != null) handler.postDelayed(this, 1000);
        }
      };

  /** 前台签到节奏：只在前台推进，进入后台立即取消，不创建后台任务或唤醒锁。 */
  private final Runnable vipCheckin =
      () -> {
        if (!visible) return;
        workers.execute(
            () -> {
              JSONObject state;
              try {
                state = api.checkinStep(false);
              } catch (Exception error) {
                return;
              }
              handler.post(
                  () -> {
                    if (!visible) return;
                    javascript(
                        "window.dispatchEvent(new CustomEvent('ting:checkin',{detail:"
                            + state
                            + "}));");
                    JSONObject status = state.optJSONObject("status");
                    if (status != null)
                      javascript(
                          "window.dispatchEvent(new CustomEvent('ting:vip',{detail:"
                              + status
                              + "}));");
                    long next = state.optLong("nextInMs", 0);
                    if (next > 0)
                      handler.postDelayed(this.vipCheckin, Math.max(1000, Math.min(next, 60000)));
                  });
            });
      };

  private void startVipCheckin() {
    handler.removeCallbacks(vipCheckin);
    handler.post(vipCheckin);
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    api = new KugouApi(this);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          starReady, new IntentFilter(StarPrompt.READY), Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(starReady, new IntentFilter(StarPrompt.READY));
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          cacheChanged, new IntentFilter(MusicCache.CHANGED), Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(cacheChanged, new IntentFilter(MusicCache.CHANGED));
    try {
      updates = new AppUpdater(this);
    } catch (Exception ignored) {
    }
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          updateDownload,
          new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
          Context.RECEIVER_EXPORTED);
    else
      registerReceiver(updateDownload, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    startService(new Intent(this, PlaybackService.class));
    web = new WebView(this);
    if (Build.VERSION.SDK_INT >= 30)
      web.setOnApplyWindowInsetsListener(
          (view, insets) -> {
            boolean shown = insets.isVisible(android.view.WindowInsets.Type.ime());
            if (shown != imeVisible) {
              imeVisible = shown;
              if (visible && loaded)
                javascript(
                    "window.dispatchEvent(new CustomEvent('ting:keyboard',{detail:{visible:"
                        + shown
                        + "}}));");
            }
            return insets;
          });
    web.setBackgroundColor(0xffffffff);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setMediaPlaybackRequiresUserGesture(true);
    settings.setSupportZoom(false);
    if (Build.VERSION.SDK_INT >= 29) settings.setForceDark(WebSettings.FORCE_DARK_OFF);
    if (Build.VERSION.SDK_INT >= 33) settings.setAlgorithmicDarkeningAllowed(false);
    web.addJavascriptInterface(new Bridge(), "Native");
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !request.getUrl().toString().startsWith(ORIGIN);
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!uri.toString().startsWith(ORIGIN)) return null;
            String path = uri.getPath();
            if (path == null || path.contains("..")) return emptyResponse();
            if (path.equals("/")) path = "/index.html";
            try {
              String mime =
                  path.endsWith(".js")
                      ? "application/javascript"
                      : path.endsWith(".css")
                          ? "text/css"
                          : path.endsWith(".svg") ? "image/svg+xml" : "text/html";
              return new WebResourceResponse(mime, "UTF-8", getAssets().open("web" + path));
            } catch (IOException exception) {
              return emptyResponse();
            }
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            loaded = true;
            sentInitialState = false;
            sendSystemAppearance();
            handler.removeCallbacks(tick);
            handler.post(tick);
            PlaybackService player = PlaybackService.instance;
            if (player != null
                && getSharedPreferences("preferences", MODE_PRIVATE)
                    .getBoolean("resumeOnLaunch", true)
                && getSharedPreferences("playback", MODE_PRIVATE).getBoolean("wasPlaying", false))
              try {
                player.execute("resume", new JSONObject());
              } catch (Exception ignored) {
              }
          }
        });
    setContentView(web);
    web.loadUrl(ORIGIN);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
  }

  private void handleBack() {
    if (!loaded) {
      moveTaskToBack(true);
      return;
    }
    web.evaluateJavascript(
        "window.tingHandleBack ? window.tingHandleBack() : false",
        result -> {
          if (!"true".equals(result)) moveTaskToBack(true);
        });
  }

  @Override
  public void onBackPressed() {
    handleBack();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == 11) {
      try {
        JSONObject status = NotificationAccess.status(this, new JSONObject());
        javascript(
            "window.dispatchEvent(new CustomEvent('ting:notification-result',{detail:"
                + status
                + "}));");
      } catch (Exception ignored) {
      }
    }
  }

  private WebResourceResponse emptyResponse() {
    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
  }

  private void javascript(String script) {
    if (!isFinishing() && !isDestroyed()) web.evaluateJavascript(script, null);
  }

  private void resolve(String id, Object result, String error) {
    String encoded =
        result == null
            ? "null"
            : result instanceof String ? JSONObject.quote((String) result) : result.toString();
    handler.post(
        () ->
            javascript(
                "window.nativeResolve&&window.nativeResolve("
                    + JSONObject.quote(id)
                    + ","
                    + encoded
                    + ","
                    + JSONObject.quote(error)
                    + ");"));
  }

  private void sendState(JSONObject state) {
    javascript("window.nativePlayback&&window.nativePlayback(" + state + ");");
  }

  private void sendState(JSONObject state, boolean progressOnly) {
    if (progressOnly) state.remove("queue");
    sendState(state);
  }

  final class Bridge {
    @JavascriptInterface
    public void request(String id, String action, String source) {
      if (source.length() > 3 * 1024 * 1024) {
        resolve(id, null, "请求内容过大");
        return;
      }
      if (action.equals("starStatus")
          || action.equals("starConsume")
          || action.equals("openRepository")) {
        handler.post(
            () -> {
              try {
                StarPrompt prompt = new StarPrompt(MainActivity.this);
                if (action.equals("openRepository")) {
                  startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(prompt.repositoryUrl())));
                  resolve(id, true, null);
                } else
                  resolve(
                      id, action.equals("starStatus") ? prompt.status() : prompt.consume(), null);
              } catch (Exception error) {
                resolve(id, null, friendlyError(error));
              }
            });
        return;
      }
      if (action.equals("notificationStatus") || action.equals("notificationOpen")) {
        handler.post(
            () -> {
              try {
                if (action.equals("notificationOpen")) NotificationAccess.open(MainActivity.this);
                resolve(
                    id, NotificationAccess.status(MainActivity.this, new JSONObject(source)), null);
              } catch (Exception failure) {
                resolve(id, null, "无法读取通知设置，请在系统设置中管理TvT通知");
              }
            });
        return;
      }
      if (java.util.Arrays.asList(
                  "search",
                  "qualityInfo",
                  "lyrics",
                  "lyricDocument",
                  "cacheSong",
                  "cacheList",
                  "cacheRemove",
                  "account",
                  "logout",
                  "qrKey",
                  "qrCheck",
                  "sendCode",
                  "loginMobile",
                  "chooseLoginAccount",
                  "cancelMobileLogin",
                  "cloudPlaylists",
                  "playlist")
              .contains(action)
          || java.util.Arrays.asList(
                  "catalog",
                  "favorites",
                  "favorite",
                  "settings",
                  "autoVip",
                  "vipStatus",
                  "checkinStatus",
                  "checkinStep",
                  "checkinProbe",
                  "updateSettings",
                  "updateCheck",
                  "updateDownload",
                  "updateStatus",
                  "updateInstall",
                  "updateReleases")
              .contains(action)) {
        workers.execute(
            () -> {
              try {
                JSONObject data = new JSONObject(source);
                Object result;
                if (action.startsWith("update")) {
                  if (updates == null) throw new Exception("更新配置读取失败");
                  if (action.equals("updateSettings")) result = updates.settings(data);
                  else if (action.equals("updateCheck"))
                    result = updates.check(data.optBoolean("manual"));
                  else if (action.equals("updateDownload")) result = updates.download();
                  else if (action.equals("updateStatus")) result = updates.status();
                  else {
                    Intent target =
                        action.equals("updateInstall")
                            ? updates.installation()
                            : updates.releases();
                    handler.post(
                        () -> {
                          try {
                            startActivity(target);
                            resolve(id, true, null);
                          } catch (Exception error) {
                            resolve(id, null, "无法打开系统安装器或浏览器");
                          }
                        });
                    return;
                  }
                } else if (action.equals("search"))
                  result = api.search(data.optString("keyword"), data.optInt("page", 1));
                else if (action.equals("lyrics")) result = api.lyrics(data);
                else if (action.equals("lyricDocument")) result = api.lyricDocument(data);
                else if (action.equals("account")) result = api.account();
                else if (action.equals("qualityInfo")) result = api.qualityInfo(data);
                else if (action.equals("catalog"))
                  result =
                      api.catalog(
                          data.optString("route"),
                          data.optJSONObject("data") == null
                              ? new JSONObject()
                              : data.getJSONObject("data"));
                else if (action.equals("favorites"))
                  result = api.favorites(data.optBoolean("refresh"));
                else if (action.equals("favorite")) {
                  result =
                      new JSONObject()
                          .put(
                              "liked",
                              api.setFavorite(
                                  data.getJSONObject("track"), data.optBoolean("liked")));
                  if (PlaybackService.instance != null)
                    handler.post(() -> PlaybackService.instance.refreshFavoriteState());
                } else if (action.equals("settings")) {
                  result = api.settings(data);
                  if (data.has("preloadAudio"))
                    handler.post(
                        () -> {
                          if (PlaybackService.instance != null)
                            PlaybackService.instance.refreshBuffer();
                        });
                  if (data.has("lyricsTranslation"))
                    handler.post(
                        () -> {
                          if (PlaybackService.instance != null)
                            PlaybackService.instance.loadFloatingLyrics();
                        });
                } else if (action.equals("autoVip")) result = api.autoVip(data.optBoolean("force"));
                else if (action.equals("vipStatus"))
                  result = api.vipStatus(data.optBoolean("refresh"));
                else if (action.equals("checkinStatus")) result = api.checkinStatus();
                else if (action.equals("checkinStep"))
                  result = api.checkinStep(data.optBoolean("manual", true));
                else if (action.equals("checkinProbe")) result = api.checkinProbe();
                else if (action.equals("logout")) {
                  api.logout();
                  result = true;
                } else if (action.equals("qrKey")) result = api.qrKey();
                else if (action.equals("qrCheck")) result = api.qrCheck(data.optString("key"));
                else if (action.equals("sendCode")) result = api.sendCode(data.optString("mobile"));
                else if (action.equals("loginMobile")) {
                  result = api.loginMobile(data.optString("mobile"), data.optString("code"));
                  handler.post(MainActivity.this::startVipCheckin);
                } else if (action.equals("chooseLoginAccount")) {
                  result = api.chooseLoginAccount(data.optString("key"));
                  handler.post(MainActivity.this::startVipCheckin);
                } else if (action.equals("cancelMobileLogin")) {
                  api.cancelMobileLogin();
                  result = true;
                } else if (action.equals("cloudPlaylists")) result = api.cloudPlaylists();
                else if (action.equals("playlist"))
                  result = api.playlist(data.optString("id"), data.optInt("page", 1));
                else if (action.equals("cacheList"))
                  result = new MusicCache(MainActivity.this).list();
                else if (action.equals("cacheRemove")) {
                  new MusicCache(MainActivity.this).remove(data.optString("hash"));
                  result = true;
                } else if (action.equals("cacheSong"))
                  result =
                      new MusicCache(MainActivity.this)
                          .start(
                              api,
                              data,
                              getSharedPreferences("playback", MODE_PRIVATE)
                                  .getString("quality", "128"));
                else throw new Exception("不支持的请求");
                resolve(id, result, null);
              } catch (Exception exception) {
                resolve(id, null, friendlyError(exception));
              }
            });
      } else
        handler.post(
            () -> {
              try {
                if (action.equals("floatingSettings")) {
                  JSONObject updates = new JSONObject(source);
                  FloatingLyrics.settings(MainActivity.this, updates);
                  if (updates.length() > 0 && PlaybackService.instance != null)
                    PlaybackService.instance.refreshFloatingLyrics();
                  resolve(id, FloatingLyrics.settings(MainActivity.this, new JSONObject()), null);
                  return;
                }
                if (action.equals("overlay")) {
                  boolean enabled = new JSONObject(source).optBoolean("enabled");
                  if (enabled && !android.provider.Settings.canDrawOverlays(MainActivity.this)) {
                    startActivity(new Intent(MainActivity.this, LyricsPermissionActivity.class));
                    resolve(id, null, "请允许悬浮窗权限，返回后将自动开启桌面歌词");
                    return;
                  }
                  api.settings(new JSONObject().put("floatingLyrics", enabled));
                  if (PlaybackService.instance != null)
                    PlaybackService.instance.loadFloatingLyrics();
                  resolve(id, true, null);
                  return;
                }
                if (action.equals("appearance")) {
                  boolean dark = new JSONObject(source).optBoolean("dark");
                  getWindow().setStatusBarColor(dark ? 0xff0b0b0d : 0xffffffff);
                  getWindow().setNavigationBarColor(dark ? 0xff0b0b0d : 0xffffffff);
                  getWindow()
                      .getDecorView()
                      .setSystemUiVisibility(
                          dark
                              ? 0
                              : android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                  | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                } else if (action.equals("exportLibrary")) {
                  pendingExport = source;
                  startActivityForResult(
                      new Intent(Intent.ACTION_CREATE_DOCUMENT)
                          .setType("application/json")
                          .addCategory(Intent.CATEGORY_OPENABLE)
                          .putExtra(Intent.EXTRA_TITLE, "TvT资料库.json"),
                      20);
                } else if (action.equals("importLibrary")) {
                  startActivityForResult(
                      new Intent(Intent.ACTION_OPEN_DOCUMENT)
                          .setType("application/json")
                          .addCategory(Intent.CATEGORY_OPENABLE),
                      21);
                } else if (action.equals("downloads")) {
                  startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                } else if (action.equals("import")) {
                  Intent picker =
                      new Intent(Intent.ACTION_OPEN_DOCUMENT)
                          .setType("audio/*")
                          .addCategory(Intent.CATEGORY_OPENABLE)
                          .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                          .addFlags(
                              Intent.FLAG_GRANT_READ_URI_PERMISSION
                                  | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                  startActivityForResult(picker, 10);
                } else {
                  dispatchPlayer(id, action, new JSONObject(source));
                  return;
                }
                resolve(id, true, null);
              } catch (Exception exception) {
                resolve(id, null, friendlyError(exception));
              }
            });
    }
  }

  private String friendlyError(Exception error) {
    if (error instanceof javax.net.ssl.SSLException) return "登录服务的安全连接失败，请稍后重试";
    if (error instanceof org.json.JSONException) return "音乐服务返回异常，请稍后重试";
    if (error instanceof java.net.UnknownHostException
        || error instanceof java.net.ConnectException) return "网络连接失败，请检查网络后重试";
    if (error instanceof java.net.SocketTimeoutException) return "音乐服务响应超时，请稍后重试";
    return error.getMessage() == null ? "操作失败，请稍后重试" : error.getMessage();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if ((request == 20 || request == 21)
        && result == RESULT_OK
        && data != null
        && data.getData() != null) {
      Uri uri = data.getData();
      workers.execute(
          () -> {
            try {
              if (request == 20) {
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                  if (output == null || pendingExport == null) throw new IOException("无法写入备份文件");
                  output.write(pendingExport.getBytes("UTF-8"));
                  pendingExport = null;
                }
                handler.post(
                    () ->
                        javascript(
                            "window.dispatchEvent(new"
                                + " CustomEvent('ting:message',{detail:'资料库已备份'}));"));
              } else {
                try (InputStream input = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                  if (input == null) throw new IOException("无法读取备份文件");
                  byte[] buffer = new byte[8192];
                  int size;
                  while ((size = input.read(buffer)) != -1) {
                    if (bytes.size() + size > 4 * 1024 * 1024) throw new IOException("备份文件过大");
                    bytes.write(buffer, 0, size);
                  }
                  JSONObject backup = new JSONObject(bytes.toString("UTF-8"));
                  handler.post(
                      () ->
                          javascript(
                              "window.dispatchEvent(new CustomEvent('ting:backup',{detail:"
                                  + backup
                                  + "}));"));
                }
              }
            } catch (Exception exception) {
              handler.post(
                  () ->
                      javascript(
                          "window.dispatchEvent(new"
                              + " CustomEvent('ting:message',{detail:'资料库文件处理失败，请检查文件格式与存储权限'}));"));
            }
          });
      return;
    }
    if (request != 10 || result != RESULT_OK || data == null) return;
    java.util.List<Uri> uris = new java.util.ArrayList<>();
    if (data.getClipData() != null) {
      for (int i = 0; i < Math.min(1000, data.getClipData().getItemCount()); i++)
        uris.add(data.getClipData().getItemAt(i).getUri());
    } else if (data.getData() != null) uris.add(data.getData());
    workers.execute(
        () -> {
          JSONArray tracks = new JSONArray();
          for (Uri uri : uris) {
            try {
              getContentResolver()
                  .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
              JSONObject track = readTrack(uri);
              tracks.put(track);
            } catch (Exception ignored) {
            }
          }
          handler.post(
              () -> javascript("window.nativeImported&&window.nativeImported(" + tracks + ");"));
        });
  }

  private JSONObject readTrack(Uri uri) throws Exception {
    String filename = "本地音乐";
    try (Cursor cursor =
        getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) filename = cursor.getString(0);
    }
    JSONObject track =
        new JSONObject()
            .put("id", uri.toString())
            .put("uri", uri.toString())
            .put("hash", "")
            .put("title", filename)
            .put("artist", "未知歌手")
            .put("album", "")
            .put("cover", "")
            .put("duration", 0);
    MediaMetadataRetriever metadata = new MediaMetadataRetriever();
    try {
      metadata.setDataSource(this, uri);
      String title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
      String artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
      String album = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
      String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      if (title != null) track.put("title", title);
      if (artist != null) track.put("artist", artist);
      if (album != null) track.put("album", album);
      if (duration != null) track.put("duration", Long.parseLong(duration) / 1000);
    } catch (RuntimeException ignored) {
    } finally {
      metadata.release();
    }
    return track;
  }

  @Override
  protected void onResume() {
    super.onResume();
    ensurePlayer();
    visible = true;
    sentInitialState = false;
    sendSystemAppearance();
    if (web != null) {
      web.onResume();
      web.resumeTimers();
      javascript(
          "document.body && document.body.classList.remove('backgrounded');"
              + " window.dispatchEvent(new Event('ting:resume'));");
      if (Build.VERSION.SDK_INT >= 30)
        javascript(
            "window.dispatchEvent(new CustomEvent('ting:keyboard',{detail:{visible:"
                + imeVisible
                + "}}));");
    }
    handler.removeCallbacks(tick);
    handler.post(tick);
    startVipCheckin();
  }

  private void sendSystemAppearance() {
    if (web == null || !loaded) return;
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    javascript(
        "window.tingSystemDark="
            + dark
            + ";window.dispatchEvent(new CustomEvent('ting:appearance',{detail:{dark:"
            + dark
            + "}}));");
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    sendSystemAppearance();
  }

  @Override
  protected void onStop() {
    if (serviceBound) {
      unbindService(playerConnection);
      serviceBound = false;
    }
    super.onStop();
  }

  @Override
  protected void onPause() {
    visible = false;
    handler.removeCallbacks(tick);
    handler.removeCallbacks(vipCheckin);
    if (PlaybackService.instance != null) {
      PlaybackService.instance.observer = null;
      PlaybackService.instance.checkpoint();
    }
    if (web != null) {
      javascript("document.body.classList.add('backgrounded');");
      web.onPause();
      web.pauseTimers();
    }
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    api.cancelMobileLogin();
    if (serviceBound) {
      unbindService(playerConnection);
      serviceBound = false;
    }
    unregisterReceiver(updateDownload);
    unregisterReceiver(cacheChanged);
    unregisterReceiver(starReady);
    handler.removeCallbacksAndMessages(null);
    workers.shutdownNow();
    if (PlaybackService.instance != null) {
      PlaybackService.instance.observer = null;
      if (!PlaybackService.instance.snapshot().optBoolean("playing"))
        stopService(new Intent(this, PlaybackService.class));
    }
    web.removeJavascriptInterface("Native");
    web.destroy();
    super.onDestroy();
  }
}
