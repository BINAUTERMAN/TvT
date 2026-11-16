package cn.ting.music;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import okhttp3.*;
import javax.net.SocketFactory;

/** 仅限制本应用的 HTTP 连接地址族，域名、证书校验与系统代理保持正常处理。 */
public final class AppHttp {
  private static boolean initialized;
  private static OkHttpClient client;
  private static final ThreadPoolExecutor closeWorker = new ThreadPoolExecutor(
      0, 1, 2, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "ting-http-close");
        thread.setDaemon(true);
        return thread;
      });

  public static synchronized void initialize(Context context) {
    if (!initialized) {
      OkHttp.INSTANCE.initialize(context.getApplicationContext());
      initialized = true;
    }
  }

  static List<InetAddress> ipv4(List<InetAddress> addresses) throws UnknownHostException {
    if (addresses == null) throw new UnknownHostException("未返回可用 IPv4 地址");
    List<InetAddress> result = new ArrayList<>();
    for (InetAddress address : addresses) if (address instanceof Inet4Address) result.add(address);
    if (result.isEmpty()) throw new UnknownHostException("当前服务没有可用 IPv4 地址");
    return result;
  }

  private static synchronized OkHttpClient client() {
    if (client == null) client = new OkHttpClient.Builder()
        .dns(host -> ipv4(Dns.SYSTEM.lookup(host)))
        .socketFactory(new Ipv4Sockets())
        .retryOnConnectionFailure(false)
        .connectionPool(new ConnectionPool(4, 30, TimeUnit.SECONDS))
        .build();
    return client;
  }

  public static HttpURLConnection open(URL url) throws IOException {
    if (url == null) throw new IOException("网络地址为空");
    if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http"))
      throw new IOException("不支持的网络协议");
    if (url.getHost().contains(":")) throw new UnknownHostException("当前连接仅支持 IPv4");
    return new Connection(url);
  }

  private static final class Ipv4Sockets extends SocketFactory {
    @Override public Socket createSocket() {
      return new Socket() {
        @Override public void connect(SocketAddress target, int timeout) throws IOException {
          if (target instanceof InetSocketAddress
              && ((InetSocketAddress) target).getAddress() instanceof Inet6Address)
            throw new UnknownHostException("当前连接仅支持 IPv4");
          super.connect(target, timeout);
        }
      };
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
      Socket socket = createSocket();
      try { socket.connect(new InetSocketAddress(host, port)); return socket; }
      catch (IOException error) { socket.close(); throw error; }
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
      return createSocket(ipv4(Dns.SYSTEM.lookup(host)).get(0), port);
    }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
      Socket socket = createSocket();
      try { socket.bind(new InetSocketAddress(local, localPort)); socket.connect(new InetSocketAddress(host, port)); return socket; }
      catch (IOException error) { socket.close(); throw error; }
    }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
      return createSocket(ipv4(Dns.SYSTEM.lookup(host)).get(0), port, local, localPort);
    }
  }

  private static final class Connection extends HttpURLConnection {
    private final ByteArrayOutputStream upload = new ByteArrayOutputStream();
    private volatile Call call;
    private volatile Response response;
    private volatile boolean cancelled;
    private final java.util.concurrent.atomic.AtomicBoolean closeQueued = new java.util.concurrent.atomic.AtomicBoolean();

    Connection(URL url) { super(url); }

    @Override public void connect() throws IOException { execute(); }
    @Override public boolean usingProxy() { return false; }
    @Override public OutputStream getOutputStream() throws IOException {
      if (connected) throw new ProtocolException("请求已经发送");
      doOutput = true;
      if (method.equals("GET")) method = "POST";
      return upload;
    }
    private synchronized Response execute() throws IOException {
      if (cancelled) throw new IOException("请求已取消");
      if (response != null) return response;
      Request.Builder request = new Request.Builder().url(url.toString());
      for (Map.Entry<String, List<String>> header : getRequestProperties().entrySet())
        for (String value : header.getValue()) request.addHeader(header.getKey(), value);
      RequestBody body = doOutput ? RequestBody.create(upload.toByteArray(),
          MediaType.parse(getRequestProperty("Content-Type") == null ? "application/octet-stream" : getRequestProperty("Content-Type"))) : null;
      request.method(method, body);
      OkHttpClient configured = client().newBuilder()
          .connectTimeout(getConnectTimeout(), TimeUnit.MILLISECONDS)
          .readTimeout(getReadTimeout(), TimeUnit.MILLISECONDS)
          .writeTimeout(getReadTimeout(), TimeUnit.MILLISECONDS)
          .followRedirects(getInstanceFollowRedirects())
          .followSslRedirects(false)
          .build();
      Call started = configured.newCall(request.build());
      call = started;
      if (cancelled) { started.cancel(); throw new IOException("请求已取消"); }
      Response received = started.execute();
      if (cancelled) { received.close(); throw new IOException("请求已取消"); }
      response = received;
      connected = true;
      return received;
    }
    @Override public int getResponseCode() throws IOException { return execute().code(); }
    @Override public String getResponseMessage() throws IOException { return execute().message(); }
    @Override public InputStream getInputStream() throws IOException {
      Response received = execute();
      if (received.code() >= 400) throw new IOException("服务返回 HTTP " + received.code());
      return received.body().byteStream();
    }
    @Override public InputStream getErrorStream() {
      try { Response received = execute(); return received.code() >= 400 ? received.body().byteStream() : null; }
      catch (IOException error) { return null; }
    }
    @Override public String getHeaderField(String name) {
      try {
        Response received = execute();
        return name == null ? "HTTP/1.1 " + received.code() + " " + received.message() : received.header(name);
      } catch (IOException error) { return null; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
      try { return execute().headers().toMultimap(); } catch (IOException error) { return Collections.emptyMap(); }
    }
    @Override public void disconnect() {
      cancelled = true;
      Call active = call;
      if (active != null) active.cancel();
      Response received = response;
      // 关闭未读完的响应可能读尾部，取消立即执行，关闭交给短寿命线程。
      if (received != null && closeQueued.compareAndSet(false, true))
        closeWorker.execute(received::close);
    }
  }
}
