package cn.ting.music;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** 音乐目录与账号权益的固定接口映射，界面不能传入任意请求地址。 */
final class CatalogApi {
  private final KugouApi api;

  CatalogApi(KugouApi api) {
    this.api = api;
  }

  private TreeMap<String, String> params(String... entries) {
    TreeMap<String, String> result = new TreeMap<>();
    for (int index = 0; index < entries.length; index += 2)
      result.put(entries[index], entries[index + 1]);
    return result;
  }

  private long id(JSONObject data) throws Exception {
    String id = data.optString("id");
    if (!id.matches("[0-9]{1,16}")) throw new Exception("音乐条目标识无效");
    return Long.parseLong(id);
  }

  JSONObject execute(String route, JSONObject data) throws Exception {
    int page = Math.max(1, Math.min(1000, data.optInt("page", 1)));
    JSONObject result;
    switch (route) {
      case "search":
        {
          String type = data.optString("type", "song");
          String keyword = data.optString("keyword").trim();
          if (keyword.isEmpty() || keyword.length() > 200) throw new Exception("请输入搜索关键词");
          if (type.equals("song")) return api.search(keyword, page);
          if (!type.equals("author")
              && !type.equals("album")
              && !type.equals("special")
              && !type.equals("lyric")) throw new Exception("搜索类型无效");
          TreeMap<String, String> query =
              params(
                  "keyword",
                  keyword,
                  "page",
                  String.valueOf(page),
                  "pagesize",
                  "30",
                  "platform",
                  "AndroidFilter",
                  "iscorrection",
                  "1",
                  "albumhide",
                  "0",
                  "nocollect",
                  "0");
          if (type.equals("album")) {
            query.put("clientver", "201");
            query.put("category", "1");
            query.put("sorttype", "0");
            query.put("searchsong", "0");
          }
          result =
              api.catalogRequest(
                  "https://gateway.kugou.com/v1/search/" + type,
                  "complexsearch.kugou.com",
                  query,
                  null);
          break;
        }
      case "searchSuggest":
        {
          String keyword = data.optString("keyword").trim();
          if (keyword.isEmpty() || keyword.length() > 200) throw new Exception("请输入搜索关键词");
          result =
              api.catalogRequest(
                  "https://gateway.kugou.com/v2/getSearchTip",
                  "searchtip.kugou.com",
                  params(
                      "keyword",
                      keyword,
                      "AlbumTipCount",
                      "3",
                      "CorrectTipCount",
                      "3",
                      "MVTipCount",
                      "0",
                      "MusicTipCount",
                      "10",
                      "radiotip",
                      "0"),
                  null);
          break;
        }
      case "searchHot":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/api/v3/search/hot_tab",
                "msearch.kugou.com",
                params("navid", "1", "plat", "2"),
                null);
        break;
      case "daily":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/everyday_song_recommend",
                "everydayrec.service.kugou.com",
                params("platform", "ios"),
                "");
        break;
      case "recommend":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/concepts/v1/ai/recommend_song",
                "",
                params(),
                new JSONObject()
                    .put("apiver", 2)
                    .put("source", 2)
                    .put("pagesize", 30)
                    .put("page", page));
        break;
      case "playlists":
        {
          long time = System.currentTimeMillis() / 1000;
          JSONObject special =
              new JSONObject()
                  .put("withtag", 1)
                  .put("withsong", data.optInt("withsong", data.optInt("category") > 0 ? 0 : 1))
                  .put("sort", data.optInt("sort", 1))
                  .put("ugc", 1)
                  .put("is_selected", 0)
                  .put("withrecommend", data.optInt("withrecommend", 1))
                  .put("area_code", 1)
                  .put("categoryid", Math.max(0, data.optInt("category")));
          JSONObject body =
              new JSONObject()
                  .put("appid", 3116)
                  .put("mid", api.deviceMid())
                  .put("clientver", 11440)
                  .put("platform", "android")
                  .put("clienttime", String.valueOf(time))
                  .put("userid", api.accountUserId())
                  .put("module_id", 1)
                  .put("page", page)
                  .put("pagesize", 20)
                  .put("key", KugouApi.md5("3116LnT6xpN3khm36zse0QzvmgTZ3waWdRSA11440" + time))
                  .put("special_recommend", special)
                  .put("req_multi", 1)
                  .put("retrun_min", 5)
                  .put("return_special_falg", 1);
          result =
              api.catalogRequest(
                  "https://gateway.kugou.com/v2/special_recommend",
                  "specialrec.service.kugou.com",
                  params(),
                  body);
          JSONObject pageData = result.optJSONObject("data");
          JSONArray pageLists = pageData == null ? null : pageData.optJSONArray("special_list");
          if (result.optInt("status") == 1
              && page == 1
              && data.optInt("category") > 0
              && (pageLists == null || pageLists.length() == 0)) {
            special.put("withrecommend", 0).put("withsong", 0);
            result =
                api.catalogRequest(
                    "https://gateway.kugou.com/v2/special_recommend",
                    "specialrec.service.kugou.com",
                    params(),
                    body);
          }
          break;
        }
      case "ranks":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/ocean/v6/rank/list",
                "",
                params("plat", "2", "withsong", "1", "parentid", "0"),
                null);
        break;
      case "rankSongs":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/openapi/kmr/v2/rank/audio",
                "",
                params(),
                new JSONObject()
                    .put("show_portrait_mv", 1)
                    .put("show_type_total", 1)
                    .put("filter_original_remarks", 1)
                    .put("area_code", 1)
                    .put("pagesize", 50)
                    .put("rank_cid", 0)
                    .put("type", 1)
                    .put("page", page)
                    .put("rank_id", id(data)));
        break;
      case "artist":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/kmr/v3/author",
                "openapi.kugou.com",
                params(),
                new JSONObject().put("author_id", id(data)));
        break;
      case "artistSongs":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/openapi/kmr/v2/audio_group/author",
                "",
                params(
                    "author_id",
                    String.valueOf(id(data)),
                    "area_code",
                    "all",
                    "sort",
                    "1",
                    "page",
                    String.valueOf(page),
                    "pagesize",
                    "50",
                    "replace_api_version",
                    "1",
                    "mvdata_need",
                    "1",
                    "show_audio_honor",
                    "1",
                    "show_audio_tag",
                    "1",
                    "replace_need",
                    "1"),
                null);
        break;
      case "artistAlbums":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/kmr/v1/author/albums",
                "openapi.kugou.com",
                params(),
                new JSONObject()
                    .put("author_id", id(data))
                    .put("pagesize", 30)
                    .put("page", page)
                    .put("sort", 3)
                    .put("category", 1)
                    .put("area_code", "all"));
        break;
      case "album":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/kmr/v2/albums",
                "openapi.kugou.com",
                params(),
                new JSONObject()
                    .put("data", new JSONArray().put(new JSONObject().put("album_id", id(data))))
                    .put("is_buy", 0)
                    .put(
                        "fields",
                        "album_id,album_name,publish_date,sizable_cover,intro,language,authors,author_name"));
        break;
      case "albumSongs":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/v1/album_audio/lite",
                "openapi.kugou.com",
                params(),
                new JSONObject()
                    .put("album_id", id(data))
                    .put("is_buy", "")
                    .put("page", page)
                    .put("pagesize", 50));
        break;
      case "playlist":
        return api.playlist(data.optString("id"), page);
      case "playlistDetail":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/v3/get_list_info",
                "pubsongs.kugou.com",
                params(),
                new JSONObject()
                    .put(
                        "data",
                        new JSONArray()
                            .put(
                                new JSONObject().put("global_collection_id", data.optString("id"))))
                    .put("userid", api.accountUserId())
                    .put("token", api.accountToken()));
        break;
      case "tags":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/pubsongs/v1/get_tags_by_type",
                "",
                params(),
                new JSONObject().put("tag_type", "collection").put("tag_id", 0).put("source", 3));
        break;
      case "scenes":
        result =
            api.catalogRequest("https://gateway.kugou.com/scene/v1/scene/list", "", params(), null);
        break;
      case "sceneSongs":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/genesisapi/v1/scene_music/rec_music",
                "",
                params(
                    "scene_id",
                    String.valueOf(id(data)),
                    "page",
                    String.valueOf(page),
                    "pagesize",
                    "30"),
                new JSONObject().put("exposure", new JSONArray()));
        break;
      case "vip":
        result =
            api.catalogRequest(
                "https://kugouvip.kugou.com/v1/get_union_vip",
                "",
                params("busi_type", "concept"),
                null);
        break;
      case "vipRecord":
        result =
            api.catalogRequest(
                "https://gateway.kugou.com/youth/v1/activity/get_month_vip_record",
                "",
                params("latest_limit", "100"),
                null);
        break;
      case "claimVip":
        {
          if (api.accountToken().isEmpty()) throw new Exception("请先登录酷狗账号");
          String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
          result =
              api.catalogRequest(
                  "https://gateway.kugou.com/youth/v1/recharge/receive_vip_listen_song",
                  "",
                  params("source_id", "90139", "receive_day", today),
                  "");
          break;
        }
      default:
        throw new Exception("不支持的音乐目录请求");
    }
    boolean vipRoute = route.equals("vip") || route.equals("vipRecord") || route.equals("claimVip");
    if ((result.has("status") && result.optInt("status") == 0)
        || (vipRoute
            && (result.optInt("status") != 1
                || result.optInt("error_code", result.optInt("errcode")) != 0))) {
      int code = result.optInt("error_code", result.optInt("errcode", 0));
      String message =
          result.optString(
              "error_msg", result.optString("msg", result.optString("error", "音乐服务暂不可用")));
      throw new Exception(
          (message.isEmpty() ? "音乐服务暂不可用" : message) + (code == 0 ? "" : "（" + code + "）"));
    }
    return result;
  }
}
