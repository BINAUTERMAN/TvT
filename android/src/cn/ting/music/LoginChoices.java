package cn.ting.music;

import java.util.*;
import org.json.*;

/** 多账号验证仅在内存短暂保留，界面只能提交临时选择键。 */
final class LoginChoices {
  final String mobile, code;
  private final long expires;
  private final Map<String, Long> users = new LinkedHashMap<>();
  private final JSONArray choices = new JSONArray();

  LoginChoices(String mobile, String code, JSONObject response, long now) throws Exception {
    this.mobile = mobile;
    this.code = code;
    expires = now + 180000;
    JSONObject data = response.optJSONObject("data");
    JSONArray list = data == null ? null : data.optJSONArray("info_list");
    if (list != null)
      for (int index = 0; index < Math.min(list.length(), 20); index++) {
        JSONObject item = list.optJSONObject(index);
        if (item == null || item.optLong("userid") <= 0) continue;
        long user = item.getLong("userid");
        if (users.containsValue(user)) continue;
        String key = UUID.randomUUID().toString();
        users.put(key, user);
        String name = item.optString("nickname").trim();
        choices.put(
            new JSONObject()
                .put("key", key)
                .put("name", name.isEmpty() ? "酷狗账号 " + (choices.length() + 1) : name)
                .put("avatar", item.optString("pic").replace("http:", "https:")));
      }
    if (choices.length() == 0) throw new Exception("平台要求选择账号，但未返回可用账号，请重新获取验证码");
  }

  JSONObject view() throws Exception {
    return new JSONObject()
        .put("requiresAccount", true)
        .put("choices", new JSONArray(choices.toString()));
  }

  long select(String key, long now) throws Exception {
    if (now >= expires) throw new Exception("本次账号选择已过期，请重新获取验证码");
    Long user = users.get(key);
    if (user == null) throw new Exception("账号选择无效，请选择平台返回的账号");
    return user;
  }
}
