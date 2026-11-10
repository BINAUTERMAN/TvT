package cn.ting.music.tests;

import android.app.Activity;
import android.media.*;
import android.os.*;

/** 用另一个进程的真实音频焦点验证打断与恢复。 */
public final class FocusProbeActivity extends Activity {
  private AudioManager audio;
  private AudioFocusRequest focus;
  private AudioTrack track;
  private boolean started;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    android.widget.TextView label = new android.widget.TextView(this);
    label.setText("正在验证音频中断，几秒后自动返回");
    label.setGravity(android.view.Gravity.CENTER);
    setContentView(label);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (!hasFocus || started) return;
    started = true;
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    int gain = getIntent().getIntExtra("gain", AudioManager.AUDIOFOCUS_GAIN);
    focus =
        new AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> {})
            .build();
    int result = audio.requestAudioFocus(focus);
    android.util.Log.i(
        "TingFocusProbe", "focusGranted=" + (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED));
    short[] samples = new short[22050 * 8];
    for (int i = 0; i < samples.length; i++)
      samples[i] = (short) (Math.sin(2 * Math.PI * 330 * i / 22050) * 60);
    track =
        new AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setSampleRate(22050)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.length * 2)
            .build();
    track.write(samples, 0, samples.length);
    track.play();
    new Handler(Looper.getMainLooper()).postDelayed(this::finish, 6500);
  }

  @Override
  public void onDestroy() {
    if (track != null) {
      track.stop();
      track.release();
    }
    if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
    super.onDestroy();
  }
}
