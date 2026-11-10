package cn.ting.music;

import java.nio.file.*;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LoginProtocolCheck {
  public static void main(String[] args) throws Exception {
    JSONObject fixture =
        new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8"));
    String key = fixture.getString("sessionKey");
    String plain = fixture.getString("plain");
    if (!fixture.getString("aes").equals(LoginProtocol.encryptSession(plain, key)))
      throw new AssertionError("AES protocol mismatch");
    if (!plain.equals(LoginProtocol.decryptSession(fixture.getString("aes"), key)))
      throw new AssertionError("AES response decoding mismatch");
    if (!fixture.getString("rsa").equals(LoginProtocol.rsa(fixture.getString("rsaPlain"))))
      throw new AssertionError("RSA padding or key mismatch");
    if (!fixture
        .getString("t1")
        .equals(
            LoginProtocol.encrypt(
                "|1234567890", "5e4ef500e9597fe004bd09a46d8add98", "04bd09a46d8add98")))
      throw new AssertionError("Device protocol mismatch");
    System.out.println("PASS login encryption matches independent Node crypto oracle");
    LoginChoices choices =
        new LoginChoices(
            "13800000000",
            "123456",
            new JSONObject()
                .put(
                    "data",
                    new JSONObject()
                        .put(
                            "info_list",
                            new JSONArray()
                                .put(
                                    new JSONObject()
                                        .put("userid", 123)
                                        .put("nickname", "测试甲")
                                        .put("token", "must-not-leak"))
                                .put(new JSONObject().put("userid", 456).put("nickname", "测试乙")))),
            1000);
    JSONObject view = choices.view();
    if (!view.getBoolean("requiresAccount")
        || view.getJSONArray("choices").length() != 2
        || view.toString().contains("must-not-leak")
        || view.toString().contains("userid")
        || view.toString().contains("123456"))
      throw new AssertionError("Account choices leak private fields");
    String selected = view.getJSONArray("choices").getJSONObject(1).getString("key");
    if (choices.select(selected, 2000) != 456) throw new AssertionError("Wrong selected account");
    boolean expired = false, invalid = false;
    try {
      choices.select(selected, 181000);
    } catch (Exception expected) {
      expired = true;
    }
    try {
      choices.select("arbitrary-user-id", 2000);
    } catch (Exception expected) {
      invalid = true;
    }
    if (!expired || !invalid) throw new AssertionError("Account selection validity");
    System.out.println("PASS 34175 account selection, private field filtering and expiry");
  }
}
