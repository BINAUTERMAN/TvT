package cn.ting.music;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;

/** 仅分析本应用播放会话；未授权时静默回退，不打开麦克风。 */
final class FmVisualizer implements AutoCloseable {
  interface Listener { void levels(float[] values); }
  private Visualizer effect;
  private int session = -1;
  private final Context context;
  private final Listener listener;

  FmVisualizer(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
  }

  boolean start(int audioSession) {
    if (effect != null && session == audioSession) return true;
    close();
    if (audioSession <= 0) return false;
    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      return false;
    try {
      Visualizer created = new Visualizer(audioSession);
      effect = created;
      session = audioSession;
      created.setCaptureSize(256);
      created.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
      created.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
        @Override public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {}
        @Override public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
          if (effect != visualizer) return;
          float[] values = new float[5];
          int[] bands = {1, 3, 8, 18, 40, 100};
          for (int band = 0; band < 5; band++) {
            double energy = 0;
            int count = 0;
            for (int bin = bands[band]; bin < bands[band + 1] && bin * 2 + 1 < fft.length; bin++) {
              double re = fft[bin * 2], im = fft[bin * 2 + 1];
              energy += re * re + im * im;
              count++;
            }
            double amplitude = Math.sqrt(energy / Math.max(1, count));
            values[band] = (float) Math.max(.15, Math.min(1, Math.log1p(amplitude) / Math.log(65)));
          }
          listener.levels(values);
        }
      }, Math.min(10000, Visualizer.getMaxCaptureRate()), false, true);
      if (created.setEnabled(true) != Visualizer.SUCCESS) throw new IllegalStateException();
      return true;
    } catch (RuntimeException | LinkageError unavailable) {
      close();
      return false;
    }
  }

  @Override public void close() {
    Visualizer previous = effect;
    effect = null;
    session = -1;
    if (previous != null) {
      try { previous.setEnabled(false); } catch (RuntimeException ignored) {}
      try { previous.release(); } catch (RuntimeException ignored) {}
    }
  }
}
