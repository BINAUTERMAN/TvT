package cn.ting.music;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.*;

final class LyricsScreen {
  static Context windowContext(Context source) {
    if (Build.VERSION.SDK_INT < 30) return source;
    return source
        .createDisplayContext(
            source
                .getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY))
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
  }

  static Rect full(WindowManager windows) {
    DisplayMetrics metrics = new DisplayMetrics();
    windows.getDefaultDisplay().getRealMetrics(metrics);
    return new Rect(0, 0, Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels));
  }

  private static WindowInsets displayInsets(WindowManager windows) {
    if (Build.VERSION.SDK_INT >= 30) {
      Rect area = full(windows);
      WindowMetrics metrics = windows.getCurrentWindowMetrics();
      Rect reported = metrics.getBounds();
      // 旋转时窗口配置可能晚于物理屏幕更新，不能混用两个方向的尺寸和边距。
      if (reported.width() == area.width() && reported.height() == area.height())
        return metrics.getWindowInsets();
    }
    return null;
  }

  static int statusBarHeight(Context context, WindowManager windows) {
    WindowInsets insets = displayInsets(windows);
    if (insets != null) {
      int top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
      if (top > 0) return top;
    }
    return dimension(context, "status_bar_height");
  }

  static Rect safe(Context context, WindowManager windows, WindowInsets delivered) {
    Rect area = full(windows);
    // 回调边距相对于悬浮窗，会随窗口位置改变；整屏坐标只能使用整屏边距。
    WindowInsets insets = Build.VERSION.SDK_INT >= 30 ? displayInsets(windows) : delivered;
    int left = 0, top = dimension(context, "status_bar_height"), right = 0, bottom = 0;
    if (insets != null && Build.VERSION.SDK_INT >= 30) {
      android.graphics.Insets bars =
          insets.getInsetsIgnoringVisibility(
              WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      left = bars.left;
      top = bars.top;
      right = bars.right;
      bottom = bars.bottom;
    } else if (insets != null) {
      left = insets.getStableInsetLeft();
      top = insets.getStableInsetTop();
      right = insets.getStableInsetRight();
      bottom = insets.getStableInsetBottom();
    } else {
      DisplayMetrics app = new DisplayMetrics();
      windows.getDefaultDisplay().getMetrics(app);
      if (area.width() > app.widthPixels) right = area.width() - app.widthPixels;
      if (area.height() > app.heightPixels)
        bottom = Math.max(0, area.height() - app.heightPixels - top);
    }
    DisplayCutout cutout = null;
    if (Build.VERSION.SDK_INT >= 29) cutout = windows.getDefaultDisplay().getCutout();
    else if (Build.VERSION.SDK_INT >= 28 && insets != null) cutout = insets.getDisplayCutout();
    if (Build.VERSION.SDK_INT >= 28 && cutout != null) {
      left = Math.max(left, cutout.getSafeInsetLeft());
      top = Math.max(top, cutout.getSafeInsetTop());
      right = Math.max(right, cutout.getSafeInsetRight());
      bottom = Math.max(bottom, cutout.getSafeInsetBottom());
    }
    left = Math.min(left, area.width() - 1);
    right = Math.min(right, area.width() - left - 1);
    top = Math.min(top, area.height() - 1);
    bottom = Math.min(bottom, area.height() - top - 1);
    area.inset(left, top, right, bottom);
    return area;
  }

  static int dimension(Context context, String name) {
    int resource = context.getResources().getIdentifier(name, "dimen", "android");
    return resource == 0 ? 0 : context.getResources().getDimensionPixelSize(resource);
  }
}
