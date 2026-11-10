package cn.ting.music;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** 账号令牌只在原生层保存，密钥由系统保管。 */
public final class AccountStore {
  private final Context context;

  public AccountStore(Context context) {
    this.context = context;
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias("ting.account")) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  "ting.account", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey("ting.account", null);
  }

  public synchronized JSONObject read() {
    try {
      String stored =
          context.getSharedPreferences("account", Context.MODE_PRIVATE).getString("data", "");
      if (stored.isEmpty()) return new JSONObject();
      String[] parts = stored.split(":");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key(),
          new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
      return new JSONObject(
          new String(
              cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
    } catch (Exception ignored) {
      return new JSONObject();
    }
  }

  public synchronized void save(JSONObject account) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String data =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":"
            + Base64.encodeToString(
                cipher.doFinal(account.toString().getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    context
        .getSharedPreferences("account", Context.MODE_PRIVATE)
        .edit()
        .putString("data", data)
        .apply();
  }

  public void clear() {
    context.getSharedPreferences("account", Context.MODE_PRIVATE).edit().clear().apply();
  }
}
