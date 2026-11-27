package android.media;

/** 仅供 JVM 网络协议测试加载；不用于模拟设备解码。 */
public abstract class MediaDataSource implements java.io.Closeable {
  public abstract int readAt(long position, byte[] buffer, int offset, int size)
      throws java.io.IOException;

  public abstract long getSize() throws java.io.IOException;
}
