package cn.ting.music.tests;

import android.app.*;
import android.content.Context;
import android.os.*;
import java.io.InputStream;
import java.util.concurrent.*;

final class DeviceLaunch {
  static Activity start(Instrumentation test, Context target) throws Exception {
    Activity[] activity = new Activity[1];
    CountDownLatch resumed = new CountDownLatch(1);
    Application application = (Application) target.getApplicationContext();
    Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
      public void onActivityResumed(Activity value) { activity[0] = value; resumed.countDown(); }
      public void onActivityCreated(Activity value, Bundle state) {}
      public void onActivityStarted(Activity value) {}
      public void onActivityPaused(Activity value) {}
      public void onActivityStopped(Activity value) {}
      public void onActivitySaveInstanceState(Activity value, Bundle state) {}
      public void onActivityDestroyed(Activity value) {}
    };
    application.registerActivityLifecycleCallbacks(callbacks);
    try {
      // 从 shell 启动，避免 singleTop 复用时 startActivitySync 一直等不到新实例。
      try (ParcelFileDescriptor descriptor = test.getUiAutomation()
          .executeShellCommand("am start -n cn.ting.music/.MainActivity");
          InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
        byte[] buffer = new byte[1024];
        while (input.read(buffer) != -1) {}
      }
      if (!resumed.await(12, TimeUnit.SECONDS)) throw new AssertionError("设备界面启动超时");
      return activity[0];
    } finally {
      application.unregisterActivityLifecycleCallbacks(callbacks);
    }
  }
}
