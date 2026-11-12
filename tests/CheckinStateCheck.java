package cn.ting.music;

import org.json.*;

/** 签到奖励的纯逻辑边界：探活基线、领取分类、节奏与互斥判断。 */
public final class CheckinStateCheck {
  public static void main(String[] args) throws Exception {
    if (!CheckinRepository.probePassed(new JSONObject().put("error_code", 20002)))
      throw new AssertionError("未登录基线必须判为接口正常");
    if (!CheckinRepository.probePassed(new JSONObject().put("errcode", 20002)))
      throw new AssertionError("兼容 errcode 字段名");
    if (CheckinRepository.probePassed(new JSONObject().put("error_code", 20001)))
      throw new AssertionError("错误码偏离基线必须判为接口失效");
    if (CheckinRepository.probePassed(new JSONObject().put("status", 1)))
      throw new AssertionError("匿名请求拿到成功响应不能算探活通过");
    if (CheckinRepository.probePassed(null)) throw new AssertionError("空响应不能通过探活");

    if (CheckinRepository.claimVerdict(new JSONObject().put("status", 1)) != 0)
      throw new AssertionError("status=1 应为成功");
    if (CheckinRepository.claimVerdict(new JSONObject().put("error_code", 30002)) != 1)
      throw new AssertionError("30002 应为今日次数用光");
    if (CheckinRepository.claimVerdict(new JSONObject().put("error_code", 20002)) != 2)
      throw new AssertionError("20002 应为登录失效");
    if (CheckinRepository.claimVerdict(new JSONObject().put("error_code", 50000)) != 3)
      throw new AssertionError("其他错误码应为失败");
    if (CheckinRepository.claimVerdict(new JSONObject().put("status", 0)) != 3)
      throw new AssertionError("无错误码的失败响应不能当作成功");

    long now = 1_700_000_000_000L;
    if (CheckinRepository.shouldRetryProbe(true, false, now - 60_000, now))
      throw new AssertionError("失败探活不能短时间重复请求");
    if (!CheckinRepository.shouldRetryProbe(true, false, now - 900_000, now))
      throw new AssertionError("失败探活应由程序在前台入口到期重试");
    if (CheckinRepository.shouldRetryProbe(true, true, now - 900_000, now))
      throw new AssertionError("正常探活不应重复请求");
    if (CheckinRepository.nextDelayMs(0, 0, now, false) != 0)
      throw new AssertionError("当天尚未领取时应立即领取");
    if (CheckinRepository.nextDelayMs(1, now - 30_000, now, false) != 30_000)
      throw new AssertionError("自动节奏应按一分钟补齐间隔");
    if (CheckinRepository.nextDelayMs(1, now - 90_000, now, false) != 0)
      throw new AssertionError("超过一分钟即可继续领取");
    if (CheckinRepository.nextDelayMs(1, now - 2_000, now, true) != 3_000)
      throw new AssertionError("手动领取仍需最小间隔防重复提交");
    if (CheckinRepository.nextDelayMs(8, 0, now, true) != 0)
      throw new AssertionError("领满后不再安排下一次");

    if (!CheckinRepository.rewardUsedToday("2026-11-11", "reward", "2026-11-11", 3))
      throw new AssertionError("当天已领奖励应占用当日口径");
    if (CheckinRepository.rewardUsedToday("2026-11-10", "reward", "2026-11-11", 3))
      throw new AssertionError("跨天不应占用当日口径");
    if (CheckinRepository.rewardUsedToday("2026-11-11", "listen", "2026-11-11", 3))
      throw new AssertionError("畅听领取模式不算奖励占用");
    if (CheckinRepository.rewardUsedToday("2026-11-11", "reward", "2026-11-11", 0))
      throw new AssertionError("未领到任何奖励时不应阻止畅听领取");

    if (!CheckinRepository.transportFailure(new java.io.IOException("timeout")))
      throw new AssertionError("IO 异常属于网络失败");
    if (!CheckinRepository.transportFailure(new Exception("音乐服务暂不可用，请稍后重试")))
      throw new AssertionError("HTTP 失败属于网络失败");
    if (!CheckinRepository.transportFailure(new Exception("包装", new java.io.IOException())))
      throw new AssertionError("嵌套 IO 异常属于网络失败");
    if (CheckinRepository.transportFailure(new Exception("平台未返回 VIP 权益信息")))
      throw new AssertionError("业务错误不能当成网络失败");

    System.out.println("PASS 探活基线、领取分类、领取节奏与两种领取方式互斥");
  }
}
