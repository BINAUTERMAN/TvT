package cn.ting.music;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** 使用真实本地 Socket 验证 Range、重复定位和磁盘复用，不代替真机解码。 */
public final class AudioStreamingCheck {
  private static int checks;

  private static void check(boolean passed, String name) {
    if (!passed) throw new AssertionError(name);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    byte[] content = new byte[AudioBlockCache.BLOCK_BYTES * 4 + 37];
    new Random(32).nextBytes(content);
    AtomicLong transferred = new AtomicLong();
    AtomicInteger requests = new AtomicInteger();
    AtomicBoolean badRange = new AtomicBoolean();
    CountDownLatch bodyStarted = new CountDownLatch(1), releaseBody = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/audio",
        exchange -> {
          try {
            String value = exchange.getRequestHeaders().getFirst("Range");
            if (value == null || !value.matches("bytes=\\d+-\\d+")) {
              badRange.set(true);
              exchange.sendResponseHeaders(400, -1);
              return;
            }
            String[] range = value.substring(6).split("-");
            int start = Integer.parseInt(range[0]),
                end = Math.min(content.length - 1, Integer.parseInt(range[1]));
            if (end - start + 1 > AudioBlockCache.BLOCK_BYTES) badRange.set(true);
            requests.incrementAndGet();
            exchange
                .getResponseHeaders()
                .set("Content-Range", "bytes " + start + "-" + end + "/" + content.length);
            exchange.sendResponseHeaders(206, end - start + 1);
            exchange.getResponseBody().write(content, start, end - start + 1);
            transferred.addAndGet(end - start + 1);
          } finally {
            exchange.close();
          }
        });
    server.createContext(
        "/wrong",
        exchange -> {
          try {
            exchange.getResponseHeaders().set("Content-Range", "bytes 1-2/10");
            exchange.sendResponseHeaders(206, 2);
            exchange.getResponseBody().write(new byte[2]);
          } finally {
            exchange.close();
          }
        });
    server.createContext(
        "/stall",
        exchange -> {
          try {
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-262143/262144");
            exchange.sendResponseHeaders(206, 262144);
            exchange.getResponseBody().write(new byte[1024]);
            exchange.getResponseBody().flush();
            bodyStarted.countDown();
            try {
              releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          } finally {
            exchange.close();
          }
        });
    server.start();
    Path root = Files.createTempDirectory(Paths.get("build/test"), "stream-");
    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    NetworkMediaSource.ConnectionFactory http = url -> AppHttp.open(new URL(endpoint + "/audio"));
    NetworkMediaSource.CacheFactory disk =
        length -> {
          try {
            return new AudioBlockCache(root.toFile(), "u1-track-flac", length);
          } catch (IOException error) {
            if (length <= 0) return null;
            throw error;
          }
        };
    try {
      NetworkMediaSource source = new NetworkMediaSource("https://example.com/audio", disk, http);
      byte[] result = new byte[7000];
      for (long position :
          new long[] {
            0,
            content.length - 20,
            11,
            AudioBlockCache.BLOCK_BYTES + 137,
            0,
            content.length - 20,
            AudioBlockCache.BLOCK_BYTES + 177
          }) {
        int count = source.readAt(position, result, 0, result.length);
        check(count > 0, "probe returns bytes");
        check(
            Arrays.equals(
                Arrays.copyOf(result, count),
                Arrays.copyOfRange(content, (int) position, (int) position + count)),
            "probe bytes match");
      }
      check(!badRange.get(), "all HTTP ranges have bounded ends");
      check(
          transferred.get() <= AudioBlockCache.BLOCK_BYTES * 2L + 37,
          "repeat probes download only unique blocks");
      check(!source.fullyCached(), "holes do not produce a complete cache");
      source.close();
      int before = requests.get();
      source = new NetworkMediaSource("https://example.com/rotated-address", disk, http);
      source.readAt(13, result, 0, result.length);
      source.readAt(content.length - 10, result, 0, result.length);
      check(
          requests.get() == before,
          "partial cache survives process-style recreation with no duplicate request");
      for (int position = 0; position < content.length; position += AudioBlockCache.BLOCK_BYTES)
        source.readAt(position, result, 0, result.length);
      check(source.fullyCached(), "complete only after every block is read");
      check(
          transferred.get() == content.length,
          "total payload equals one whole file despite repeated seeks");
      source.close();
      try (AudioBlockCache reopened = new AudioBlockCache(root.toFile(), "u1-track-flac", -1)) {
        check(reopened.complete(), "full cache survives reopen");
        check(
            Arrays.equals(content, Files.readAllBytes(reopened.file().toPath())),
            "cached file byte equality");
      }
      try {
        new NetworkMediaSource(
            "https://example.com/wrong", null, url -> AppHttp.open(new URL(endpoint + "/wrong")));
        throw new AssertionError("wrong range accepted");
      } catch (IOException expected) {
        checks++;
      }
      byte[] partial = new byte[AudioBlockCache.BLOCK_BYTES];
      try (AudioBlockCache holes =
          new AudioBlockCache(root.toFile(), "holes", AudioBlockCache.BLOCK_BYTES * 2L)) {
        holes.put(AudioBlockCache.BLOCK_BYTES, partial, partial.length);
        check(
            holes.read(0, partial) == 0 && !holes.complete(),
            "sparse file length does not imply downloaded data");
        try {
          holes.put(0, partial, 10);
          throw new AssertionError("short block accepted");
        } catch (IOException expected) {
          checks++;
        }
      }
      try (AudioBlockCache changed = new AudioBlockCache(root.toFile(), "holes", 100)) {
        check(
            changed.read(0, partial) == 0 && !changed.complete(),
            "changed file length resets old ranges");
      }
      long largeLength = AudioBlockCache.MAX_FILE_BYTES + AudioBlockCache.BLOCK_BYTES;
      try (AudioBlockCache large = new AudioBlockCache(root.toFile(), "hires", largeLength)) {
        large.put(largeLength - partial.length, partial, partial.length);
      }
      try (AudioBlockCache large = new AudioBlockCache(root.toFile(), "hires", -1)) {
        check(
            large.read(largeLength - partial.length, partial) == partial.length,
            "Hi-Res above ahead-file limit still retains played blocks");
      }
      try {
        new AudioBlockCache(root.toFile(), "oversize", AudioBlockCache.MAX_TOTAL_BYTES + 1);
        throw new AssertionError("cache budget exceeded");
      } catch (IOException expected) {
        checks++;
      }
      try {
        new AudioBlockCache(root.toFile(), "../escape", 10);
        throw new AssertionError("path accepted");
      } catch (IOException expected) {
        checks++;
      }
      try (NetworkMediaSource noDisk =
          new NetworkMediaSource(
              "https://example.com/no-space",
              length -> {
                throw new IOException("disk full");
              },
              http)) {
        check(
            noDisk.readAt(0, result, 0, result.length) > 0,
            "disk failure keeps network playback available");
      }
      NetworkMediaSource stalled =
          new NetworkMediaSource(
              "https://example.com/stall", null, url -> AppHttp.open(new URL(endpoint + "/stall")));
      AtomicReference<Throwable> outcome = new AtomicReference<>();
      Thread reader =
          new Thread(
              () -> {
                try {
                  stalled.readAt(0, new byte[1024], 0, 1024);
                  outcome.set(new AssertionError("cancelled read returned data"));
                } catch (IOException expected) {
                } catch (Throwable failure) {
                  outcome.set(failure);
                }
              });
      reader.start();
      check(bodyStarted.await(2, TimeUnit.SECONDS), "real stalled HTTP body reached");
      long closeAt = System.nanoTime();
      stalled.close();
      reader.join(2000);
      check(
          !reader.isAlive() && outcome.get() == null, "closing source cancels active Socket read");
      check(
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeAt) < 2000,
          "source close does not wait for read timeout");
      releaseBody.countDown();
      System.out.println(
          "AUDIO_STREAMING_PASS "
              + checks
              + " checks; real HTTP ranges, repeated probes, persistent blocks, holes, corruption"
              + " boundaries and disk failure");
    } finally {
      releaseBody.countDown();
      server.stop(0);
    }
  }
}
