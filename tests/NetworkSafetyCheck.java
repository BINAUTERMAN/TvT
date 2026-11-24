package cn.ting.music;

import java.net.*;
import java.util.*;
import org.json.*;

public final class NetworkSafetyCheck {
  public static void main(String[] args) throws Exception {
    InetAddress v6 = InetAddress.getByAddress(new byte[16]);
    InetAddress v4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    if (!AppHttp.ipv4(Arrays.asList(v6, v4)).equals(Collections.singletonList(v4)))
      throw new AssertionError("IPv6 未被过滤");
    for (List<InetAddress> addresses : Arrays.<List<InetAddress>>asList(null, Collections.emptyList(), Collections.singletonList(v6))) {
      try { AppHttp.ipv4(addresses); throw new AssertionError("无 IPv4 时必须明确失败"); }
      catch (UnknownHostException expected) {}
    }
    try { AppHttp.open(null); throw new AssertionError("空地址必须拒绝"); }
    catch (java.io.IOException expected) {}
    try { AppHttp.open(new URL("https://[::1]/")); throw new AssertionError("IPv6 字面地址必须拒绝"); }
    catch (UnknownHostException expected) {}
    HttpURLConnection headers = AppHttp.open(new URL("https://example.com/"));
    java.lang.reflect.Field response = headers.getClass().getDeclaredField("response"); response.setAccessible(true);
    response.set(headers, new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url("https://example.com/").build())
        .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(okhttp3.ResponseBody.create(new byte[0], null)).build());
    if (!headers.getHeaderField(null).contains("200")) throw new AssertionError("空字段名应返回状态行");
    headers.disconnect();
    java.lang.reflect.Constructor<?> socketConstructor = Class.forName("cn.ting.music.AppHttp$Ipv4Sockets").getDeclaredConstructor();
    socketConstructor.setAccessible(true);
    javax.net.SocketFactory sockets = (javax.net.SocketFactory) socketConstructor.newInstance();
    try (Socket socket = sockets.createSocket()) {
      try { socket.connect(new InetSocketAddress(v6, 443), 100); throw new AssertionError("IPv6 Socket 绕过限制"); }
      catch (UnknownHostException expected) {}
    }
    if (KugouApi.stopsQualityFallback(new Exception())) throw new AssertionError("无消息异常应可继续音质回退");
    if (!KugouApi.stopsQualityFallback(new java.io.IOException())) throw new AssertionError("网络失败不能伪装成音质不可用");
    if (!KugouApi.stopsQualityFallback(new Exception("需要安全验证"))) throw new AssertionError("不能绕过安全验证");
    JSONArray options = QualityCatalog.options(null);
    if (options.length() != 4 || !QualityCatalog.hash(null, "flac").isEmpty())
      throw new AssertionError("空音质响应处理错误");
    for (int index = 0; index < options.length(); index++)
      if (options.getJSONObject(index).getBoolean("exists")) throw new AssertionError("空数据不能伪造音质");
    JSONObject malformed = new JSONObject().put("relate_goods", new JSONArray().put(JSONObject.NULL)
        .put(new JSONObject().put("quality", "flac").put("info", JSONObject.NULL)));
    if (!QualityCatalog.hash(malformed, "flac").isEmpty()) throw new AssertionError("异常 JSON 不应生成哈希");
    JSONObject original = new JSONObject().put("relate_goods", new JSONArray().put(new JSONObject()
        .put("quality", "320").put("level", 4).put("hash", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .put("info", new JSONObject().put("filesize", 12345))))
        .put("trans_param", new JSONObject().put("ogg_320_hash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("ogg_320_filesize", 6789));
    if (!QualityCatalog.hash(original, "320").equals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        || QualityCatalog.options(original).getJSONObject(1).getLong("size") != 12345)
      throw new AssertionError("原始音质的哈希和文件大小必须一致，不能混入转码资源");
    System.out.println("NETWORK_NULL_SAFETY_PASS");
  }
}
