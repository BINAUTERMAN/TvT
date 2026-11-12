package cn.ting.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

final class NotificationIcons {
  static Icon status(Context context) {
    // 直接传入字标像素，避免系统沿用旧版本资源图标缓存。
    Drawable wordmark = context.getDrawable(R.drawable.ic_notification);
    Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
    wordmark.setBounds(0, 0, 96, 96);
    wordmark.draw(new Canvas(bitmap));
    return Icon.createWithBitmap(bitmap);
  }
}
