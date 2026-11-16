package cn.ting.music;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RequestWorkersCheck {
  public static void main(String[] args) throws Exception {
    RequestWorkers workers = new RequestWorkers();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocked = new CountDownLatch(3);
    Runnable slow = () -> {
      blocked.countDown();
      try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    };
    try {
      workers.execute("vipStatus", slow);
      workers.execute("search", slow);
      workers.execute("catalog", slow);
      if (!blocked.await(3, TimeUnit.SECONDS)) throw new AssertionError("慢请求未进入线程");
      for (String action : new String[] {"account", "loginMobile", "chooseLoginAccount", "qrCheck", "logout"}) {
        CountDownLatch authenticated = new CountDownLatch(1);
        workers.execute(action, authenticated::countDown);
        if (!authenticated.await(1, TimeUnit.SECONDS)) throw new AssertionError(action + " 被慢请求阻塞");
      }
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
    System.out.println("REQUEST_WORKERS_PASS: 认证不等待会员和内容线程（受控阻塞测试）");
  }
}
