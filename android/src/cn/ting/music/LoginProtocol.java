package cn.ting.music;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 保持与上游概念版登录协议的编码、填充方式一致。 */
final class LoginProtocol {
  private static final String PUBLIC_KEY =
      "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB";

  static String encrypt(String text, String key, String iv) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
        new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
    return hex(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }

  static String encryptSession(String text, String sessionKey) throws Exception {
    String key = KugouApi.md5(sessionKey);
    return encrypt(text, key, key.substring(16));
  }

  static String decryptSession(String text, String sessionKey) throws Exception {
    String key = KugouApi.md5(sessionKey);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
        new IvParameterSpec(key.substring(16).getBytes(StandardCharsets.UTF_8)));
    return new String(cipher.doFinal(unhex(text)), StandardCharsets.UTF_8);
  }

  static String rsa(String text) throws Exception {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 128) throw new IllegalArgumentException("登录参数长度无效");
    byte[] padded = new byte[128];
    System.arraycopy(bytes, 0, padded, 0, bytes.length);
    Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        KeyFactory.getInstance("RSA")
            .generatePublic(
                new X509EncodedKeySpec(java.util.Base64.getDecoder().decode(PUBLIC_KEY))));
    return hex(cipher.doFinal(padded)).toUpperCase(java.util.Locale.ROOT);
  }

  static String rsaSession(String text) throws Exception {
    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        KeyFactory.getInstance("RSA")
            .generatePublic(
                new X509EncodedKeySpec(java.util.Base64.getDecoder().decode(PUBLIC_KEY))));
    return hex(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)))
        .toUpperCase(java.util.Locale.ROOT);
  }

  static String encryptRegistration(String text, String sessionKey) throws Exception {
    String key = KugouApi.md5(sessionKey);
    return java.util.Base64.getEncoder()
        .encodeToString(unhex(encrypt(text, key.substring(0, 16), key.substring(16))));
  }

  static String decryptRegistration(byte[] data, String sessionKey) throws Exception {
    String key = KugouApi.md5(sessionKey);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key.substring(0, 16).getBytes(StandardCharsets.UTF_8), "AES"),
        new IvParameterSpec(key.substring(16).getBytes(StandardCharsets.UTF_8)));
    return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
  }

  private static byte[] unhex(String source) {
    if (source.length() % 2 != 0) throw new IllegalArgumentException("登录响应格式无效");
    byte[] bytes = new byte[source.length() / 2];
    for (int i = 0; i < bytes.length; i++)
      bytes[i] = (byte) Integer.parseInt(source.substring(i * 2, i * 2 + 2), 16);
    return bytes;
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder();
    for (byte item : bytes) value.append(String.format("%02x", item & 255));
    return value.toString();
  }
}
