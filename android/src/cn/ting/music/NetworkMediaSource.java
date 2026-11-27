package cn.ting.music;

import android.media.MediaDataSource;
import java.io.*;
import java.net.*;

/** 有界 Range 避免解码器探测位置时重复传输整个文件尾部。 */
final class NetworkMediaSource extends MediaDataSource {
  interface CacheFactory {
    AudioBlockCache open(long length) throws IOException;
  }

  interface ConnectionFactory {
    HttpURLConnection open(URL url) throws IOException;
  }

  private final URL url;
  private final CacheFactory cacheFactory;
  private final ConnectionFactory connections;
  private volatile HttpURLConnection connection;
  private volatile InputStream input;
  private volatile boolean closed;
  private volatile long position, length = -1;
  private volatile long networkBytes, cacheBytes, openCount;
  private final byte[] cache = new byte[AudioBlockCache.BLOCK_BYTES];
  private long cacheStart;
  private int cacheLength;
  private volatile AudioBlockCache disk;
  private volatile Runnable onComplete;

  void onComplete(Runnable listener) {
    onComplete = listener;
  }

  boolean cacheEnabled() {
    return disk != null;
  }

  boolean fullyCached() {
    AudioBlockCache local = disk;
    return local != null && local.complete();
  }

  NetworkMediaSource(String address) throws IOException {
    this(address, null);
  }

  NetworkMediaSource(String address, CacheFactory factory) throws IOException {
    this(address, factory, AppHttp::open);
  }

  NetworkMediaSource(String address, CacheFactory factory, ConnectionFactory connections)
      throws IOException {
    url = new URL(address);
    cacheFactory = factory;
    this.connections = connections;
    if (!url.getProtocol().equals("https")
        && !(url.getProtocol().equals("http")
            && url.getHost().equalsIgnoreCase("fs.youthandroid2.kugou.com")))
      throw new IOException("音频地址不受支持");
    if (factory != null)
      try {
        disk = factory.open(-1);
        if (disk != null) length = disk.length;
      } catch (IOException ignored) {
      }
    try {
      if (disk == null) openAt(0);
    } catch (IOException error) {
      close();
      throw error;
    }
  }

  private void openAt(long offset) throws IOException {
    disconnect();
    HttpURLConnection request = connections.open(url);
    connection = request;
    openCount++;
    if (closed) {
      request.disconnect();
      throw new IOException("音频已关闭");
    }
    long end = offset + cache.length - 1;
    if (length >= 0) end = Math.min(end, length - 1);
    request.setConnectTimeout(8000);
    request.setReadTimeout(15000);
    request.setRequestProperty("Accept-Encoding", "identity");
    request.setRequestProperty("Range", "bytes=" + offset + "-" + end);
    int status = request.getResponseCode();
    if (status != 200 && status != 206) throw new IOException("音频服务返回 HTTP " + status);
    String range = request.getHeaderField("Content-Range");
    if (status == 206) {
      java.util.regex.Matcher match =
          java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
              .matcher(range == null ? "" : range);
      if (!match.matches()) throw new IOException("音频分段响应无效");
      try {
        long first = Long.parseLong(match.group(1)), last = Long.parseLong(match.group(2));
        if (first != offset || last < first || last > end) throw new IOException("音频分段位置不符");
        if (!match.group(3).equals("*")) {
          long total = Long.parseLong(match.group(3));
          if (total <= last || (length >= 0 && length != total))
            throw new IOException("音频文件长度发生变化，请重新播放");
          length = total;
        }
      } catch (NumberFormatException invalid) {
        throw new IOException("音频分段响应无效");
      }
    } else {
      if (offset > 0) throw new IOException("音频服务不支持定位，请重新播放");
      long total = request.getContentLengthLong();
      if (length >= 0 && total >= 0 && total != length) throw new IOException("音频文件长度发生变化，请重新播放");
      length = total;
    }
    if (closed) throw new IOException("音频已关闭");
    input = request.getInputStream();
    position = offset;
    if (disk == null && cacheFactory != null && length > 0)
      try {
        AudioBlockCache opened = cacheFactory.open(length);
        if (closed) {
          if (opened != null) opened.close();
          throw new IOException("音频已关闭");
        }
        disk = opened;
      } catch (IOException ignored) {
      }
  }

  @Override
  public synchronized int readAt(long offset, byte[] buffer, int start, int count)
      throws IOException {
    if (closed) throw new IOException("音频已关闭");
    if (offset < 0 || start < 0 || count < 0 || start > buffer.length - count)
      throw new IOException("音频读取范围无效");
    if (count == 0) return 0;
    if (length >= 0 && offset >= length) return -1;
    if (offset >= cacheStart && offset < cacheStart + cacheLength) {
      int copied = Math.min(count, (int) (cacheStart + cacheLength - offset));
      System.arraycopy(cache, (int) (offset - cacheStart), buffer, start, copied);
      cacheBytes += copied;
      return copied;
    }
    long block = offset / cache.length * cache.length;
    int read = 0;
    cacheLength = 0;
    AudioBlockCache local = disk;
    if (local != null)
      try {
        read = local.read(block, cache);
        if (read > 0) cacheBytes += read;
      } catch (IOException failed) {
        disableCache(local);
      }
    if (read == 0) {
      try {
        if (connection == null || position != block) openAt(block);
        int wanted = length >= 0 ? (int) Math.min(cache.length, length - block) : cache.length;
        InputStream active = input;
        if (active == null) throw new IOException("音频已关闭");
        while (read < wanted) {
          int received = active.read(cache, read, wanted - read);
          if (closed) throw new IOException("音频已关闭");
          if (received < 0) break;
          if (received == 0) throw new IOException("音频服务未返回数据");
          read += received;
          position += received;
          networkBytes += received;
        }
        if (length >= 0 && read < wanted) throw new IOException("音频分段不完整，请重试");
      } finally {
        disconnect();
      }
      if (read == 0) return -1;
      local = disk;
      if (local != null)
        try {
          local.put(block, cache, read);
          if (local.complete()) {
            Runnable listener = onComplete;
            onComplete = null;
            if (listener != null) listener.run();
          }
        } catch (IOException failed) {
          disableCache(local);
        }
    }
    cacheStart = block;
    cacheLength = read;
    int within = (int) (offset - block);
    if (within >= read) return -1;
    int copied = Math.min(count, read - within);
    System.arraycopy(cache, within, buffer, start, copied);
    return copied;
  }

  private void disableCache(AudioBlockCache failed) {
    disk = null;
    try {
      failed.close();
    } catch (IOException ignored) {
    }
  }

  private void disconnect() {
    HttpURLConnection active = connection;
    connection = null;
    input = null;
    if (active != null) active.disconnect();
  }

  @Override
  public long getSize() {
    return length;
  }

  @Override
  public void close() {
    closed = true;
    disconnect();
    AudioBlockCache local = disk;
    if (local != null) disableCache(local);
  }
}
