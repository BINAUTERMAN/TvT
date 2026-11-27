package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import org.json.*;
import android.webkit.WebView;
import java.util.concurrent.*;

/** 真机检查冷启动续播、异步定位和稳定进度，不更换账号、曲目或输出设备。 */
public final class PlaybackPositionChecks extends Instrumentation {
  private Object service;
  private Method execute, snapshot;
  private int checks;
  private int restoreMs = 117396;
  private String requestedHash = "";
  private boolean restoreOnly;
  private boolean restorePlaying = true;
  private String restoreQuality="",restorePreload="";
  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    requestedHash=args==null?"":args.getString("trackHash","");
    restoreOnly=args!=null&&"true".equals(args.getString("restoreOnly"));
    if(args!=null&&args.containsKey("resumeMs"))restoreMs=Integer.parseInt(args.getString("resumeMs"));
    if(args!=null)restorePlaying=!"false".equals(args.getString("resumePlayback"));
    if(args!=null){restoreQuality=args.getString("quality","");restorePreload=args.getString("preloadAudio","");}
    start();
  }
  private void report(String text) {
    Bundle status = new Bundle(); status.putString("stream", text+"\n"); sendStatus(0,status);
  }
  private void check(String name, boolean pass) {
    report(name+": "+(pass?"PASS":"FAIL")); if(!pass)throw new AssertionError(name); checks++;
  }
  private Object field(String name) throws Exception {
    Field field=service.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(service);
  }
  private JSONObject state() {
    JSONObject[] value=new JSONObject[1];
    runOnMainSync(()->{try{value[0]=(JSONObject)snapshot.invoke(service);}catch(Exception error){throw new RuntimeException(error);} });return value[0];
  }
  private String js(WebView web,String script) throws Exception {
    CountDownLatch done=new CountDownLatch(1);String[] value=new String[1];
    runOnMainSync(()->web.evaluateJavascript(script,result->{value[0]=result;done.countDown();}));
    if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("WebView did not respond");return value[0];
  }
  private void lyricClick(Activity activity, Context target) throws Exception {
    Field webField=activity.getClass().getDeclaredField("web");webField.setAccessible(true);WebView web=(WebView)webField.get(activity);
    js(web,"document.querySelector('.mini-info')?.click()");SystemClock.sleep(500);
    js(web,"(()=>{if(!document.querySelector('.lyric-line'))document.querySelector('.player-tools button')?.click()})()");
    for(int i=0;i<50;i++){if(Integer.parseInt(js(web,"document.querySelectorAll('.lyric-line').length"))>8)break;SystemClock.sleep(100);}
    Class<?> apiType=target.getClassLoader().loadClass("cn.ting.music.KugouApi");Object api=apiType.getConstructor(Context.class).newInstance(target);
    JSONObject currentState=state();JSONObject track=currentState.getJSONArray("queue").getJSONObject(currentState.getInt("index"));
    JSONObject document=(JSONObject)apiType.getMethod("lyricDocument",JSONObject.class).invoke(api,track);
    double targetTime=document.getJSONArray("lines").getJSONObject(5).getDouble("time");
    js(web,"document.querySelectorAll('.lyric-line')[5].click()");awaitPlaying();
    check("Actual WebView lyric click seeks native audio",Math.abs(state().optDouble("position")-targetTime)<1);
    SystemClock.sleep(300);
    check("Clicked lyric remains highlighted after native seek",js(web,"[...document.querySelectorAll('.lyric-line')].findIndex(x=>x.classList.contains('active'))").equals("5"));
  }
  private void command(String action, JSONObject data) {
    runOnMainSync(()->{try{execute.invoke(service,action,data);}catch(Exception error){throw new RuntimeException(error);} });
  }
  private void awaitPlaying() throws Exception {
    long at=SystemClock.elapsedRealtime();
    for(int i=0;i<300;i++) {
      JSONObject value=state();
      if(value.optBoolean("playing")&&!value.optBoolean("loading")) {report("READY waitedMs="+(SystemClock.elapsedRealtime()-at)+" position="+value.optDouble("position"));return;}
      SystemClock.sleep(100);
    }
    throw new AssertionError("Playback did not become ready");
  }
  private void stableClock(String name) throws Exception {
    double previous=state().optDouble("position"),first=previous; long at=SystemClock.elapsedRealtime();
    boolean monotonic=true;
    for(int i=0;i<7;i++) {
      SystemClock.sleep(1000);JSONObject value=state();double position=value.optDouble("position");
      report("POSITION "+name+" elapsedMs="+(SystemClock.elapsedRealtime()-at)+" position="+position+" playing="+value.optBoolean("playing"));
      if(!value.optBoolean("playing"))report("PLAYBACK_PAUSED_DURING_STABILITY_CHECK");
      if(position<previous-.3)monotonic=false;previous=position;
    }
    check(name+" has no multi-second backward jump",monotonic);
    check(name+" follows elapsed playback time",previous-first>5.5&&previous-first<9);
  }
  @Override public void onStart() {
    Bundle result=new Bundle();int code=Activity.RESULT_OK;
    Context target=getTargetContext();
    try {
      SharedPreferences saved=target.getSharedPreferences("playback",0);
      if(!restoreQuality.isEmpty()){
        if(!java.util.Arrays.asList("128","320","flac","high").contains(restoreQuality))throw new Exception("Invalid restore quality");
        saved.edit().putString("quality",restoreQuality).commit();
      }
      if(!restorePreload.isEmpty())target.getSharedPreferences("preferences",0).edit().putBoolean("preloadAudio",Boolean.parseBoolean(restorePreload)).commit();
      JSONArray original=new JSONArray(saved.getString("queue","[]"));
      check("Current queue is retained",original.length()>0);
      if(!requestedHash.isEmpty()) {
        int selected=-1;
        for(int i=0;i<original.length();i++)if(requestedHash.equalsIgnoreCase(original.getJSONObject(i).optString("hash"))){selected=i;break;}
        check("Requested diagnostic track exists in current queue",selected>=0);
        saved.edit().putInt("index",selected).commit();
      }
      int duration=original.getJSONObject(saved.getInt("index",0)).optInt("duration",265);
      restoreMs=Math.min(restoreMs,Math.max(1000,(duration-5)*1000));
      saved.edit().putInt("position",restoreMs).putBoolean("wasPlaying",restoreOnly?restorePlaying:true).commit();
      if(restoreOnly) {
        result.putString("stream","PLAYBACK_STATE_RESTORED positionMs="+restoreMs+"\n");
        finish(Activity.RESULT_OK,result);return;
      }
      long started=SystemClock.elapsedRealtime();
      Activity activity=DeviceLaunch.start(this,target);
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      Class<?> type=target.getClassLoader().loadClass("cn.ting.music.PlaybackService");
      for(int i=0;i<100;i++){service=type.getField("instance").get(null);if(service!=null)break;SystemClock.sleep(100);}
      check("Playback service restored",service!=null);
      execute=type.getMethod("execute",String.class,JSONObject.class);snapshot=type.getMethod("snapshot");
      check("Saved position remains visible while loading",state().optDouble("position")>restoreMs/1000.0-1);
      awaitPlaying();
      report("COLD_READY elapsedMs="+(SystemClock.elapsedRealtime()-started));
      check("Cold start resumes saved position instead of zero",Math.abs(state().optDouble("position")-restoreMs/1000.0)<3);
      long base=(Long)field("loadStartedAt");
      for(String name:new String[]{"loadResolvedAt","loadSourceOpenedAt","loadPreparedAt","loadReadyAt"})report("LOAD_PHASE "+name+" elapsedMs="+((Long)field(name)-base));
      Object source=field("mediaSource");
      report("SOURCE_TYPE "+(source==null?"cached/local":source.getClass().getSimpleName()));
      if(source instanceof android.media.MediaDataSource)report("SOURCE_SIZE "+((android.media.MediaDataSource)source).getSize());
      android.media.MediaPlayer current=(android.media.MediaPlayer)field("media");
      for(android.media.MediaPlayer.TrackInfo info:current.getTrackInfo())report("TRACK_FORMAT "+info.getFormat());
      stableClock("Cold resume");
      double targetTime=Math.min(58.736,duration*.3);
      command("seek",new JSONObject().put("position",targetTime));awaitPlaying();
      check("Lyric seek reaches selected time",Math.abs(state().optDouble("position")-targetTime)<1);
      stableClock("Lyric seek");
      lyricClick(activity,target);
      command("seek",new JSONObject().put("position",40.125));
      command("seek",new JSONObject().put("position",80.375));
      command("seek",new JSONObject().put("position",60.625));
      awaitPlaying();
      check("Rapid seeks retain the latest target",Math.abs(state().optDouble("position")-60.625)<1);
      command("seek",new JSONObject().put("position",90.5));command("pause",new JSONObject());SystemClock.sleep(700);
      check("Pause during seek does not auto-resume",!state().optBoolean("playing")&&!state().optBoolean("loading"));
      check("Pending seek checkpoint preserves target",Math.abs(saved.getInt("position",0)-90500)<1000);
      command("resume",new JSONObject());awaitPlaying();
      check("Resume after cancellation retains position",Math.abs(state().optDouble("position")-90.5)<2);
      long loadAt=(Long)field("loadStartedAt");
      runOnMainSync(activity::recreate);SystemClock.sleep(2000);
      check("Activity recreation keeps the active player",type.getField("instance").get(null)==service&&(Long)field("loadStartedAt")==loadAt&&state().optBoolean("playing"));
      command("seek",new JSONObject().put("position",restoreMs/1000.0));awaitPlaying();
      Method checkpoint=type.getMethod("checkpoint");runOnMainSync(()->{try{checkpoint.invoke(service);}catch(Exception error){throw new RuntimeException(error);} });
      result.putString("stream","PLAYBACK_POSITION_DEVICE_PASS checks="+checks+"\n");
    } catch(Throwable error) {
      if(service!=null)try {
        JSONObject value=state();report("FAILURE loading="+value.optBoolean("loading")+" playing="+value.optBoolean("playing")+" position="+value.optDouble("position"));
        long base=(Long)field("loadStartedAt");
        for(String name:new String[]{"loadResolvedAt","loadSourceOpenedAt","loadPreparedAt","loadReadyAt"})report("LOAD_PHASE "+name+" elapsedMs="+((Long)field(name)-base));
      }catch(Exception ignored){}
      code=Activity.RESULT_CANCELED;result.putString("stream","PLAYBACK_POSITION_DEVICE_FAIL "+error+"\n");
    }
    finish(code,result);
  }
}
