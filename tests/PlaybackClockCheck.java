package cn.ting.music;

public final class PlaybackClockCheck {
  private static void check(boolean pass, String message) {
    if (!pass) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    PlaybackClock clock = new PlaybackClock();
    clock.reset(117396,0); clock.start(0);
    long[] times = {1005,2008,3012,4017,5021,6024,7029};
    long[] positions = {118379,117657,118660,117624,118630,119174,120177};
    long previous = 117396;
    for (int i=0;i<times.length;i++) {
      long value = clock.position(positions[i],times[i]);
      check(value>=previous,"Observed device trace must not move lyrics backward"); previous=value;
    }
    check(Math.abs(previous-124425)<50,"Observed trace must retain elapsed song time after native clock reset");
    clock.start(7050);
    check(clock.position(120200,7100)==124496,"Duplicate play/buffering-end event must not reset an active clock");
    clock.pause(120200,7100);
    long paused=clock.position(120200,20000);
    check(paused==clock.position(120200,30000),"Paused/buffering time must not advance lyrics");
    clock.start(30000);
    check(clock.position(120201,31000)==paused+1000,"Resume must exclude time spent paused");
    clock.reset(58000,32000); clock.start(32000);
    check(clock.position(58100,32100)==58100,"Explicit backward seek must reset the clock");
    clock.position(59000,33000); clock.speed(1.5f,33000);
    clock.position(58000,34000);
    check(clock.position(58000,35000)==62000,"Fallback clock must respect selected speed");
    clock.reset(90000,40000); clock.start(40000);
    check(clock.position(90250,41000)==90250,"Normal slow native progress must remain authoritative");
    System.out.println("PLAYBACK_CLOCK_PASS observed regression, normal native progress, seek, pause, buffering and speed");
  }
}
