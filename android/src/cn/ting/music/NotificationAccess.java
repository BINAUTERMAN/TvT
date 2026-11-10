package cn.ting.music;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;

/** 仅使用公开通知接口；厂商未公开的独立开关保留为待确认。 */
final class NotificationAccess {
  static void ensureChannel(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager.getNotificationChannel("playback") != null) return;
    NotificationChannel channel =
        new NotificationChannel("playback", "音乐播放", NotificationManager.IMPORTANCE_HIGH);
    channel.setSound(null, null);
    channel.enableVibration(false);
    channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
    channel.setShowBadge(false);
    manager.createNotificationChannel(channel);
  }

  static JSONObject status(Context context, JSONObject updates) throws Exception {
    android.content.SharedPreferences preferences =
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    if (updates.has("dontRemind"))
      preferences
          .edit()
          .putBoolean("notificationDontRemind", updates.getBoolean("dontRemind"))
          .apply();
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    NotificationChannel channel = manager.getNotificationChannel("playback");
    boolean permission =
        Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    boolean enabled =
        permission
            && manager.areNotificationsEnabled()
            && (channel == null || channel.getImportance() > NotificationManager.IMPORTANCE_NONE);
    String lockscreen =
        !enabled
                || (channel != null
                    && channel.getLockscreenVisibility() == Notification.VISIBILITY_SECRET)
            ? "disabled"
            : "unknown";
    String floating = !enabled ? "disabled" : "unknown";
    return new JSONObject()
        .put("enabled", enabled)
        .put("permission", permission)
        .put("lockscreen", lockscreen)
        .put("floating", floating)
        .put(
            "needsAttention",
            !enabled || lockscreen.equals("disabled") || floating.equals("disabled"))
        .put("dontRemind", preferences.getBoolean("notificationDontRemind", false));
  }

  static void open(Activity activity) {
    android.content.SharedPreferences preferences =
        activity.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    if (Build.VERSION.SDK_INT >= 33
        && activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        && (!preferences.getBoolean("notificationRequested", false)
            || activity.shouldShowRequestPermissionRationale(
                "android.permission.POST_NOTIFICATIONS"))) {
      preferences.edit().putBoolean("notificationRequested", true).apply();
      activity.requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 11);
      return;
    }
    activity.startActivity(
        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
  }
}
