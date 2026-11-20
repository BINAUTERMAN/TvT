package cn.ting.music.tests;

import android.app.*;
import android.content.Context;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 只读取当前应用最近的退出原因及无响应主线程，不导出账号或业务数据。 */
public final class CrashDiagnostics extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private void report(String value) {
    Bundle status = new Bundle(); status.putString("stream", value + "\n"); sendStatus(0, status);
  }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      if (Build.VERSION.SDK_INT >= 30) {
        Context target = getTargetContext();
        ActivityManager manager = target.getSystemService(ActivityManager.class);
        for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(target.getPackageName(), 0, 20)) {
          if (!exit.getProcessName().equals(target.getPackageName())) continue;
          report("EXIT time=" + exit.getTimestamp() + " reason=" + exit.getReason());
          if (exit.getReason() != ApplicationExitInfo.REASON_ANR) continue;
          try (InputStream trace = exit.getTraceInputStream()) {
            if (trace == null) continue;
            BufferedReader reader = new BufferedReader(new InputStreamReader(trace, StandardCharsets.UTF_8));
            String line; boolean main = false; int count = 0;
            while ((line = reader.readLine()) != null && count++ < 2000) {
              if (line.startsWith("\"main\"")) main = true;
              if (main) { report(line); if (line.trim().isEmpty()) break; }
            }
          }
        }
      }
      result.putString("stream", "CRASH_DIAGNOSTIC_DONE\n");
      finish(Activity.RESULT_OK, result);
    } catch (Exception error) { result.putString("stream", error.toString()); finish(Activity.RESULT_CANCELED, result); }
  }
}
