package cn.ting.music.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;

public final class CatalogChecks extends Instrumentation {
  private String only = "";

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    only = arguments.getString("only", "");
    start();
  }

  private Object schema(Object object, int depth) throws Exception {
    if (object == null || object == JSONObject.NULL) return "null";
    if (depth > 5) return object instanceof JSONArray ? "array" : "object";
    if (object instanceof JSONArray) {
      JSONArray a = (JSONArray) object;
      return new JSONObject()
          .put("count", a.length())
          .put("first", a.length() > 0 ? schema(a.get(0), depth + 1) : "empty");
    }
    if (!(object instanceof JSONObject)) return object.getClass().getSimpleName();
    JSONObject source = (JSONObject) object, result = new JSONObject();
    java.util.Iterator<String> keys = source.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      result.put(key, schema(source.get(key), depth + 1));
    }
    return result;
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      Class<?> type = getTargetContext().getClassLoader().loadClass("cn.ting.music.KugouApi");
      Object api =
          type.getConstructor(android.content.Context.class).newInstance(getTargetContext());
      for (String route :
          new String[] {
            "tags",
            "daily",
            "recommend",
            "playlists",
            "ranks",
            "artist",
            "artistSongs",
            "artistAlbums",
            "album",
            "albumSongs",
            "vip",
            "vipRecord",
            "searchSuggest",
            "search:author",
            "search:album",
            "search:special"
          }) {
        if (!only.isEmpty() && !route.equals(only)) continue;
        JSONObject data = new JSONObject().put("id", route.startsWith("album") ? "966846" : "3520");
        if (route.equals("searchSuggest")) data.put("keyword", "周杰");
        String action = route;
        if (route.startsWith("search:")) {
          action = "search";
          data.put("keyword", "周杰伦").put("type", route.substring(7));
        }
        String message;
        try {
          Object response =
              type.getMethod("catalog", String.class, JSONObject.class).invoke(api, action, data);
          message = route + ": " + schema(response, 0);
          if (route.equals("playlists")) {
            JSONObject body = ((JSONObject) response).optJSONObject("data");
            JSONArray list = body == null ? null : body.optJSONArray("special_list");
            JSONArray covers = new JSONArray();
            if (list != null)
              for (int index = 0; index < Math.min(8, list.length()); index++) {
                JSONObject item = list.getJSONObject(index), safe = new JSONObject();
                java.util.Iterator<String> keys = item.keys();
                while (keys.hasNext()) {
                  String key = keys.next();
                  if (key.matches(".*(img|pic|cover|specialname|special_name).*"))
                    safe.put(key, item.get(key));
                }
                covers.put(safe);
              }
            message += "\nPUBLIC_PLAYLIST_COVERS " + covers;
          }
          if (route.equals("vip") || route.equals("vipRecord")) {
            JSONObject body = ((JSONObject) response).optJSONObject("data");
            JSONObject summary = new JSONObject();
            if (body != null) {
              for (String key :
                  new String[] {
                    "is_vip", "vip_type", "vip_end_time", "server_time", "month", "future_duration"
                  }) if (body.has(key)) summary.put(key, body.get(key));
              JSONArray list = body.optJSONArray("list");
              if (list != null) {
                JSONArray records = new JSONArray();
                for (int index = 0; index < list.length(); index++) {
                  JSONObject item = list.optJSONObject(index), safe = new JSONObject();
                  if (item == null) continue;
                  for (String key : new String[] {"day", "receive_vip", "recharge_num", "vip_type"})
                    if (item.has(key)) safe.put(key, item.get(key));
                  records.put(safe);
                }
                summary.put("records", records);
              }
              JSONArray rights = body.optJSONArray("busi_vip");
              if (rights != null) {
                JSONArray safeRights = new JSONArray();
                for (int index = 0; index < rights.length(); index++) {
                  JSONObject item = rights.optJSONObject(index), safe = new JSONObject();
                  if (item == null) continue;
                  java.util.Iterator<String> keys = item.keys();
                  while (keys.hasNext()) {
                    String key = keys.next();
                    if (key.matches(".*(vip|time|type|expire|end|busi).*"))
                      safe.put(key, item.get(key));
                  }
                  safeRights.put(safe);
                }
                summary.put("rights", safeRights);
              }
            }
            message += "\nVIP_SAFE_SUMMARY " + summary;
          }
        } catch (java.lang.reflect.InvocationTargetException error) {
          message = route + ": ERROR " + error.getCause().getMessage();
        }
        Bundle status = new Bundle();
        status.putString("stream", message + "\n");
        sendStatus(0, status);
      }
      result.putString("stream", "CATALOG_CHECKS_FINISHED\n");
      finish(Activity.RESULT_OK, result);
    } catch (Exception error) {
      result.putString("stream", error.getClass().getSimpleName());
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
