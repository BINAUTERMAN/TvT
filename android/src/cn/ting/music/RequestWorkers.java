package cn.ting.music;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 认证、会员与内容请求隔离，慢速权益接口不能占用登录或搜索线程。 */
final class RequestWorkers {
  private final ExecutorService content = Executors.newFixedThreadPool(2);
  private final ExecutorService account = Executors.newSingleThreadExecutor();
  private final ExecutorService membership = Executors.newSingleThreadExecutor();

  void execute(Runnable task) {
    content.execute(task);
  }

  void execute(String action, Runnable task) {
    if (Arrays.asList("account", "logout", "qrKey", "qrCheck", "sendCode", "loginMobile",
        "chooseLoginAccount", "cancelMobileLogin").contains(action)) account.execute(task);
    else if (Arrays.asList("vipStatus", "autoVip", "checkinStatus", "checkinStep").contains(action))
      membership.execute(task);
    else content.execute(task);
  }

  void shutdownNow() {
    content.shutdownNow();
    account.shutdownNow();
    membership.shutdownNow();
  }
}
