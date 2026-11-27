package cn.ting.music;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 位图仅标记完整分段，未读取的稀疏区域不会作为可播放音频返回。 */
final class AudioBlockCache implements AutoCloseable {
  static final int BLOCK_BYTES = 256 * 1024;
  static final long MAX_FILE_BYTES = 48L * 1024 * 1024;
  static final long MAX_TOTAL_BYTES = MAX_FILE_BYTES * 3;
  static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
  private static final int MAGIC = 0x54565431;
  private final File audio, metadata;
  private final BitSet blocks;
  private final long createdAt;
  final long length;
  private RandomAccessFile data;

  AudioBlockCache(File root, String key, long expectedLength) throws IOException {
    if (!key.matches("[a-zA-Z0-9_-]+")) throw new IOException("音频缓存编号无效");
    if (!root.isDirectory() && !root.mkdirs()) throw new IOException("音频缓存目录不可用");
    audio = new File(root, key + ".stream");
    metadata = new File(root, key + ".ranges");
    long savedLength = 0, savedAt = 0;
    BitSet saved = null;
    try (DataInputStream input = new DataInputStream(new FileInputStream(metadata))) {
      if (input.readInt() != MAGIC || input.readInt() != BLOCK_BYTES)
        throw new IOException("缓存格式无效");
      savedLength = input.readLong();
      savedAt = input.readLong();
      int count = input.readInt();
      if (savedLength <= 0
          || savedLength > MAX_TOTAL_BYTES
          || count < 0
          || count > 128
          || savedAt <= 0
          || System.currentTimeMillis() - savedAt > MAX_AGE_MS
          || savedAt - System.currentTimeMillis() > 300000
          || (expectedLength > 0 && savedLength != expectedLength)) throw new IOException("缓存已过期");
      byte[] bitmap = new byte[count];
      input.readFully(bitmap);
      saved = BitSet.valueOf(bitmap);
      if (saved.length() > (savedLength + BLOCK_BYTES - 1) / BLOCK_BYTES
          || !audio.isFile()
          || audio.length() != savedLength) throw new IOException("缓存文件不完整");
    } catch (IOException invalid) {
      if (expectedLength <= 0) throw invalid;
      saved = null;
    }
    if (saved == null) {
      if (expectedLength <= 0 || expectedLength > MAX_TOTAL_BYTES)
        throw new IOException("音频超过缓存上限");
      if (metadata.exists() && !metadata.delete()) throw new IOException("无法重置音频缓存");
      savedLength = expectedLength;
      savedAt = System.currentTimeMillis();
      saved = new BitSet();
      try (RandomAccessFile reset = new RandomAccessFile(audio, "rw")) {
        reset.setLength(0);
        reset.setLength(savedLength);
      }
    }
    length = savedLength;
    createdAt = savedAt;
    blocks = saved;
    data = new RandomAccessFile(audio, "rw");
    try {
      save();
    } catch (IOException failed) {
      close();
      throw failed;
    }
  }

  private void save() throws IOException {
    File temporary = new File(metadata.getPath() + ".tmp");
    byte[] bitmap = blocks.toByteArray();
    try (DataOutputStream output = new DataOutputStream(new FileOutputStream(temporary))) {
      output.writeInt(MAGIC);
      output.writeInt(BLOCK_BYTES);
      output.writeLong(length);
      output.writeLong(createdAt);
      output.writeInt(bitmap.length);
      output.write(bitmap);
    }
    try {
      Files.move(
          temporary.toPath(),
          metadata.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    audio.setLastModified(System.currentTimeMillis());
  }

  synchronized int read(long offset, byte[] target) throws IOException {
    if (data == null
        || offset < 0
        || offset >= length
        || offset % BLOCK_BYTES != 0
        || !blocks.get((int) (offset / BLOCK_BYTES))) return 0;
    int count = (int) Math.min(BLOCK_BYTES, length - offset);
    data.seek(offset);
    data.readFully(target, 0, count);
    return count;
  }

  synchronized void put(long offset, byte[] source, int count) throws IOException {
    if (data == null) return;
    if (offset < 0
        || offset >= length
        || offset % BLOCK_BYTES != 0
        || count != Math.min(BLOCK_BYTES, length - offset)) throw new IOException("音频缓存分段不完整");
    data.seek(offset);
    data.write(source, 0, count);
    blocks.set((int) (offset / BLOCK_BYTES));
    save();
  }

  synchronized boolean complete() {
    return blocks.nextClearBit(0) >= (length + BLOCK_BYTES - 1) / BLOCK_BYTES;
  }

  File file() {
    return audio;
  }

  @Override
  public synchronized void close() throws IOException {
    if (data != null) {
      data.close();
      data = null;
    }
  }
}
