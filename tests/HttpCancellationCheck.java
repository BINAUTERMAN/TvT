package cn.ting.music;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okio.*;

/** 用可控读取复现取消时的竞争，同时检查真实慢响应的取消与线程回收。 */
public final class HttpCancellationCheck {
  public static void main(String[] args) throws Exception {
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      uncaught.compareAndSet(null, error);
      if (thread.getName().equals("main")) error.printStackTrace();
    });
    CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
    AtomicBoolean active = new AtomicBoolean(), overlap = new AtomicBoolean();
    AtomicInteger closes = new AtomicInteger();
    Source source = new Source() {
      public Timeout timeout() { return Timeout.NONE; }
      public long read(Buffer sink, long count) throws IOException {
        active.set(true); reading.countDown();
        try {
          if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("test read timeout");
          sink.writeByte(7); return 1;
        } catch (InterruptedException error) { throw new IOException(error); }
        finally { active.set(false); }
      }
      public void close() { overlap.set(overlap.get() || active.get()); closes.incrementAndGet(); closed.countDown(); }
    };
    BufferedSource buffered = Okio.buffer(source);
    ResponseBody body = new ResponseBody() {
      public MediaType contentType() { return null; }
      public long contentLength() { return 1024; }
      public BufferedSource source() { return buffered; }
    };
    HttpURLConnection connection = AppHttp.open(new URL("https://example.com/"));
    Field response = connection.getClass().getDeclaredField("response"); response.setAccessible(true);
    response.set(connection, new Response.Builder().request(new Request.Builder().url("https://example.com/").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build());
    InputStream input = connection.getInputStream();
    AtomicReference<Throwable> readError = new AtomicReference<>();
    Thread reader = new Thread(() -> { try { input.read(); } catch (IOException cancelled) { }
        catch (Throwable error) { readError.set(error); } }, "test-http-reader");
    reader.start();
    if (!reading.await(2, TimeUnit.SECONDS)) throw new AssertionError("reader did not start");
    long at = System.nanoTime();
    connection.disconnect(); input.close(); connection.disconnect();
    long cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at);
    try {
      if (cancelMs > 200) throw new AssertionError("cancellation blocked caller: " + cancelMs);
      if (closed.await(150, TimeUnit.MILLISECONDS)) throw new AssertionError("response closed during active read");
    } finally { release.countDown(); }
    reader.join(2000);
    if (reader.isAlive() || !closed.await(2, TimeUnit.SECONDS) || overlap.get() || closes.get() != 1
        || readError.get() != null || uncaught.get() != null) throw new AssertionError("unsafe response cleanup");
    try { input.read(); throw new AssertionError("closed stream allowed a late read"); }
    catch (IOException expected) { }
    for (int attempt = 0; attempt < 20; attempt++) cancelSocketRead();
    if (uncaught.get() != null) throw new AssertionError("uncaught cleanup failure", uncaught.get());
    Field worker = AppHttp.class.getDeclaredField("closeWorker"); worker.setAccessible(true);
    Thread.sleep(2300);
    if (((ThreadPoolExecutor) worker.get(null)).getPoolSize() != 0) throw new AssertionError("cleanup thread did not retire");
    System.out.println("HTTP_CANCELLATION_PASS serialized read/close, 20 socket cancellations, one close, no uncaught exception, worker retired");
  }

  private static void cancelSocketRead() throws Exception {
    CountDownLatch hold = new CountDownLatch(1), entered = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127,0,0,1}))) {
      Thread peer = new Thread(() -> {
        try (java.net.Socket socket = server.accept()) {
          BufferedReader request = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
          String line; while ((line = request.readLine()) != null && !line.isEmpty()) { }
          OutputStream output = socket.getOutputStream();
          output.write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\nx".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          output.flush(); hold.await(4, TimeUnit.SECONDS);
        } catch (Exception error) { failure.set(error); }
      });
      peer.start();
      HttpURLConnection connection = AppHttp.open(new URL("http://127.0.0.1:" + server.getLocalPort() + "/slow"));
      connection.setConnectTimeout(2000); connection.setReadTimeout(4000);
      try {
        InputStream input = connection.getInputStream();
        if (input.read() != 'x') throw new AssertionError("slow server response missing");
        Thread reader = new Thread(() -> {
          entered.countDown();
          try { input.read(); failure.set(new AssertionError("cancelled read succeeded")); }
          catch (IOException expected) { }
        });
        reader.start(); entered.await(1, TimeUnit.SECONDS);
        long at = System.nanoTime(); input.close(); connection.disconnect();
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at) > 200) throw new AssertionError("socket cancellation blocked");
        reader.join(1500);
        if (reader.isAlive()) throw new AssertionError("socket read was not interrupted");
      } finally { connection.disconnect(); hold.countDown(); peer.join(2000); }
      if (failure.get() != null) throw new AssertionError("socket cancellation failed", failure.get());
    }
  }
}
