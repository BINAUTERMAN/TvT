package cn.ting.music.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import org.json.*;

/** 保留当前曲目和音质，测量真实冷启动；只输出公开元数据与耗时。 */
public final class PlaybackLoadChecks extends Instrumentation {
  private Object service;
  private String requestedQuality;
  private boolean networkOnly;
  private Method snapshot;
  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    requestedQuality=args==null?"":args.getString("quality","");
    networkOnly=args!=null&&"true".equals(args.getString("networkOnly"));
    start();
  }
  private void report(String text) {
    Bundle result=new Bundle();result.putString("stream",text+"\n");sendStatus(0,result);
  }
  private Object field(Object instance,String name) throws Exception {
    Field value=instance.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(instance);
  }
  private JSONObject state() {
    JSONObject[] result=new JSONObject[1];
    runOnMainSync(()->{try{result[0]=(JSONObject)snapshot.invoke(service);}catch(Exception error){throw new RuntimeException(error);} });return result[0];
  }
  private void phases() throws Exception {
    long base=(Long)field(service,"loadStartedAt");
    for(String name:new String[]{"loadResolvedAt","loadSourceOpenedAt","loadPreparedAt","loadReadyAt"}) {
      long at=(Long)field(service,name);report("PHASE "+name+" "+(at==0?"pending":Long.toString(at-base)));
    }
  }
  private String signature(InputStream input) throws Exception {
    byte[] bytes=new byte[16];int count=input.read(bytes);
    if(count>=4&&bytes[0]=='f'&&bytes[1]=='L'&&bytes[2]=='a'&&bytes[3]=='C')return "FLAC";
    if(count>=4&&bytes[0]=='O'&&bytes[1]=='g'&&bytes[2]=='g'&&bytes[3]=='S')return "OGG";
    if(count>=3&&bytes[0]=='I'&&bytes[1]=='D'&&bytes[2]=='3')return "MP3/ID3";
    if(count>=2&&(bytes[0]&255)==255&&(bytes[1]&224)==224)return "MPEG audio";
    return "unknown";
  }
  private void sourceInfo(Context target,JSONObject track) throws Exception {
    Object source=field(service,"mediaSource");
    report("ACTUAL_QUALITY "+field(service,"resolvedQuality"));
    if(source!=null) {
      report("SOURCE network bytes="+((android.media.MediaDataSource)source).getSize());
      URL url=(URL)field(source,"url");
      Class<?> http=target.getClassLoader().loadClass("cn.ting.music.AppHttp");
      HttpURLConnection request=(HttpURLConnection)http.getMethod("open",URL.class).invoke(null,url);
      request.setConnectTimeout(3000);request.setReadTimeout(3000);
      request.setRequestProperty("Range","bytes=0-63");request.setRequestProperty("Accept-Encoding","identity");
      try(InputStream input=request.getInputStream()){report("FORMAT "+signature(input));}
      finally{request.disconnect();}
    } else report("SOURCE cached/local");
  }
  @Override public void onStart() {
    Bundle result=new Bundle();int code=Activity.RESULT_OK;
    Context target=getTargetContext();
    try {
      SharedPreferences saved=target.getSharedPreferences("playback",0);
      JSONArray queue=new JSONArray(saved.getString("queue","[]"));JSONObject track=queue.getJSONObject(saved.getInt("index",0));
      String selected=requestedQuality.isEmpty()?saved.getString("quality","128"):requestedQuality;
      report("CURRENT title="+track.optString("title")+" artist="+track.optString("artist")+" duration="+track.optDouble("duration")+" requestedQuality="+selected+" savedPositionMs="+saved.getInt("position",0));
      saved.edit().putString("quality",selected).putBoolean("wasPlaying",!networkOnly).commit();
      Activity activity=DeviceLaunch.start(this,target);
      runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      Class<?> type=target.getClassLoader().loadClass("cn.ting.music.PlaybackService");
      for(int i=0;i<100;i++){service=type.getField("instance").get(null);if(service!=null)break;SystemClock.sleep(100);}
      if(service==null)throw new Exception("Playback service unavailable");
      snapshot=type.getMethod("snapshot");
      if(networkOnly){Method load=type.getDeclaredMethod("load",boolean.class,boolean.class);load.setAccessible(true);runOnMainSync(()->{try{load.invoke(service,true,false);}catch(Exception error){throw new RuntimeException(error);} });}
      long at=SystemClock.elapsedRealtime();
      for(int i=0;i<600;i++) {
        long ready=(Long)field(service,"loadReadyAt");
        if(ready>0){report("READY waitedMs="+(SystemClock.elapsedRealtime()-at)+" playing="+state().optBoolean("playing"));break;}
        if(i%50==0){JSONObject value=state();report("WAIT elapsedMs="+(SystemClock.elapsedRealtime()-at)+" loading="+value.optBoolean("loading"));phases();}
        SystemClock.sleep(100);
      }
      phases();sourceInfo(target,track);
      if((Long)field(service,"loadReadyAt")==0)throw new Exception("Player did not become ready within test window");
      Method checkpoint=type.getMethod("checkpoint");runOnMainSync(()->{try{checkpoint.invoke(service);}catch(Exception error){throw new RuntimeException(error);} });
      saved.edit().commit();result.putString("stream","PLAYBACK_LOAD_DIAGNOSIS_DONE\n");
    }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream","PLAYBACK_LOAD_DIAGNOSIS_ERROR "+error+"\n");}
    finish(code,result);
  }
}
