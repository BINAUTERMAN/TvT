package cn.ting.music;

import java.util.*;

public final class BufferPlanCheck {
  public static void main(String[] args) {
    if (!Arrays.equals(PlaybackBuffer.plan(5, 1, 0, 2, new Random(1)), new int[] {2, 3}))
      throw new AssertionError("Sequential lookahead");
    if (PlaybackBuffer.plan(5, 4, 0, 2, new Random(1)).length != 0)
      throw new AssertionError("Queue end must not prefetch the first song");
    if (PlaybackBuffer.plan(5, 2, 1, 2, new Random(1)).length != 0)
      throw new AssertionError("Repeat-one does not need upcoming songs");
    int[] shuffled = PlaybackBuffer.plan(5, 2, 2, 2, new Random(1));
    if (shuffled.length != 2 || shuffled[0] == shuffled[1] || shuffled[0] == 2 || shuffled[1] == 2)
      throw new AssertionError("Shuffle must exclude current and duplicate songs");
    if (PlaybackBuffer.plan(1, 0, 2, 2, new Random(1)).length != 0)
      throw new AssertionError("Single-song queue");
    System.out.println("PASS sequential, queue end, repeat-one and shuffle lookahead");
  }
}
