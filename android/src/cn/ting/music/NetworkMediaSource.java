package cn.ting.music;

import android.media.MediaDataSource;
import java.io.*;
import java.net.*;

/** 原生播放器通过同一 IPv4 客户端读取在线音频，支持 Range 和小范围回读。 */
final class NetworkMediaSource extends MediaDataSource {
  private final URL url;
  private volatile HttpURLConnection connection;
  private volatile InputStream input;
  private volatile boolean closed;
  private long position;
  private long length = -1;
  private final byte[] cache = new byte[256 * 1024];
  private long cacheStart;
  private int cacheLength;

  NetworkMediaSource(String address) throws IOException {
    url = new URL(address);
    if (!url.getProtocol().equals("https")
        && !(url.getProtocol().equals("http") && url.getHost().equalsIgnoreCase("fs.youthandroid2.kugou.com")))
      throw new IOException("音频地址不受支持");
    try { openAt(0); }
    catch (IOException error) { close(); throw error; }
  }

  private void openAt(long offset) throws IOException {
    HttpURLConnection old = connection;
    if (old != null) old.disconnect();
    HttpURLConnection request = AppHttp.open(url);
    connection = request;
    if (closed) { request.disconnect(); throw new IOException("音频已关闭"); }
    request.setConnectTimeout(8000);
    request.setReadTimeout(15000);
    request.setRequestProperty("Accept-Encoding", "identity");
    request.setRequestProperty("Range", "bytes=" + offset + "-");
    int status = request.getResponseCode();
    if (status != 200 && status != 206) { request.disconnect(); throw new IOException("音频服务返回 HTTP " + status); }
    String range = request.getHeaderField("Content-Range");
    if (range != null && range.contains("/")) {
      try { length = Long.parseLong(range.substring(range.lastIndexOf('/') + 1)); }
      catch (NumberFormatException ignored) {}
    } else if (status == 200) length = request.getContentLengthLong();
    InputStream stream = request.getInputStream();
    if (status == 200 && offset > 0) {
      request.disconnect();
      throw new IOException("音频服务不支持定位，请重新播放");
    }
    if (closed) { request.disconnect(); throw new IOException("音频已关闭"); }
    input = stream;
    position = offset;
    cacheLength = 0;
  }

  @Override public synchronized int readAt(long offset, byte[] buffer, int start, int count) throws IOException {
    if (closed) throw new IOException("音频已关闭");
    if (offset < 0 || start < 0 || count < 0 || start > buffer.length - count) throw new IOException("音频读取范围无效");
    if (count == 0) return 0;
    if (length >= 0 && offset >= length) return -1;
    if (offset >= cacheStart && offset < cacheStart + cacheLength) {
      int copied = Math.min(count, (int) (cacheStart + cacheLength - offset));
      System.arraycopy(cache, (int) (offset - cacheStart), buffer, start, copied);
      return copied;
    }
    if (offset != position) openAt(offset);
    int read = input.read(cache);
    if (read < 0) return -1;
    cacheStart = offset;
    cacheLength = read;
    position += read;
    int copied = Math.min(count, read);
    System.arraycopy(cache, 0, buffer, start, copied);
    return copied;
  }

  @Override public long getSize() { return length; }

  @Override public void close() {
    closed = true;
    HttpURLConnection active = connection;
    if (active != null) active.disconnect();
  }
}
