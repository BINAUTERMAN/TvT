package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Bundle;
import java.lang.reflect.*;
import org.json.JSONObject;

/** 使用独立偏好存储验证计数，不消耗用户的首次提醒，也不发起真实领奖。 */
public final class StarChecks extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private void check(String name, boolean passed) throws Exception {
    Bundle status = new Bundle();
    status.putString("stream", name + ": " + (passed ? "PASS" : "FAIL") + "\n");
    sendStatus(0, status);
    if (!passed) throw new Exception(name);
  }

  private Method method(Class<?> type, String name, Class<?>... params) throws Exception {
    Method result = type.getDeclaredMethod(name, params);
    result.setAccessible(true);
    return result;
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    Context target = getTargetContext();
    SharedPreferences saved =
        target.getSharedPreferences("star_prompt_verification", Context.MODE_PRIVATE);
    saved.edit().clear().commit();
    try {
      int[] broadcasts = {0};
      Context isolated =
          new ContextWrapper(target) {
            @Override
            public Context getApplicationContext() {
              return this;
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
              return saved;
            }

            @Override
            public void sendBroadcast(Intent intent) {
              broadcasts[0]++;
            }
          };
      Class<?> type = target.getClassLoader().loadClass("cn.ting.music.StarPrompt");
      Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
      constructor.setAccessible(true);
      Object prompt = constructor.newInstance(isolated);
      Method started = method(type, "started", String.class),
          status = method(type, "status"),
          consume = method(type, "consume");
      check(
          "Fresh install is not eligible",
          !((JSONObject) status.invoke(prompt)).getBoolean("eligible"));
      started.invoke(prompt, "selection-1");
      started.invoke(prompt, "selection-1");
      check(
          "Pause resume and quality reload of one selection count once",
          saved.getInt("plays", 0) == 1);
      for (int index = 2; index <= 4; index++) started.invoke(prompt, "selection-" + index);
      check(
          "Four successful starts do not prompt",
          !((JSONObject) status.invoke(prompt)).getBoolean("eligible"));
      started.invoke(prompt, "selection-5");
      check(
          "Fifth successful start becomes eligible without song completion",
          ((JSONObject) status.invoke(prompt)).getBoolean("eligible"));
      check("Threshold emits one app-private event", broadcasts[0] == 1);
      Object reopened = constructor.newInstance(isolated);
      check(
          "Count persists across recreation",
          ((JSONObject) status.invoke(reopened)).getBoolean("eligible"));
      check("First display can be claimed", Boolean.TRUE.equals(consume.invoke(reopened)));
      check("Repeated display is rejected", Boolean.FALSE.equals(consume.invoke(prompt)));
      Object restarted = constructor.newInstance(isolated);
      check(
          "One-time marker persists across recreation",
          !((JSONObject) status.invoke(restarted)).getBoolean("eligible"));
      started.invoke(restarted, "selection-6");
      check("Counting stops after display", saved.getInt("plays", 0) == 5 && broadcasts[0] == 1);
      String url = (String) method(type, "repositoryUrl").invoke(prompt);
      check(
          "Prompt opens the configured TvT repository",
          url.equals("https://github.com/BINAUTERMAN/TvT"));
      ApplicationInfo app = target.getApplicationInfo();
      File output = new File(target.getExternalFilesDir(null), "branding-verification");
      output.mkdirs();
      Drawable[] icons = {app.loadIcon(target.getPackageManager()), target.getDrawable(app.icon)};
      String[] filenames = {"package-manager-icon.png", "application-resource-icon.png"};
      for (int index = 0; index < icons.length; index++) {
        Bitmap image = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888);
        icons[index].setBounds(0, 0, 144, 144);
        icons[index].draw(new Canvas(image));
        try (FileOutputStream file = new FileOutputStream(new File(output, filenames[index]))) {
          image.compress(Bitmap.CompressFormat.PNG, 100, file);
        }
        image.recycle();
      }
      check(
          "Installed application label is TvT",
          target.getPackageManager().getApplicationLabel(app).toString().equals("TvT"));
      check(
          "Installed application uses an adaptive icon",
          app.loadIcon(target.getPackageManager()) instanceof AdaptiveIconDrawable);
      result.putString("stream", "STAR_AND_BRANDING_CHECKS_PASS\n");
    } catch (Exception error) {
      code = Activity.RESULT_CANCELED;
      result.putString("stream", "STAR_CHECK_ERROR " + error.getClass().getSimpleName() + "\n");
    } finally {
      saved.edit().clear().commit();
    }
    finish(code, result);
  }
}
