package cn.ting.music;

import java.util.*;
import org.json.*;

public final class PlaylistIdentityCheck {
  private static JSONObject song(String hash, String audioId) throws Exception {
    return new JSONObject().put("hash", hash).put("audioId", audioId);
  }
  private static void check(boolean value) {
    if (!value) throw new AssertionError("Playlist identity mismatch");
  }
  public static void main(String[] args) throws Exception {
    String first = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", canonical = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    Set<String> saved = new HashSet<>();
    PlaylistImporter.addIdentity(saved, song(canonical, "123"));
    check(PlaylistImporter.containsIdentity(saved, song(first, "123")));
    check(PlaylistImporter.containsIdentity(saved, song(canonical, "0")));
    check(!PlaylistImporter.containsIdentity(saved, song(first, "124")));
    check(!PlaylistImporter.containsIdentity(saved, song(first, "0")));
    saved.clear();
    PlaylistImporter.addIdentity(saved, song(first, "0"));
    check(PlaylistImporter.containsIdentity(saved, song(first, "123")));
    check(!PlaylistImporter.containsIdentity(saved, song(canonical, "0")));
    JSONObject raw = new JSONObject().put("hash", first).put("mixsongid", 123).put("name", "歌手 - 歌曲")
        .put("albuminfo", new JSONObject().put("cover", "http://covers.test/{size}/cover.jpg"));
    JSONObject normalized = CloudPlaylistRepository.track(raw);
    check(normalized.getString("audioId").equals("123"));
    check(normalized.getString("cover").equals("https://covers.test/400/cover.jpg"));
    System.out.println("PASS playlist canonical hash, missing audio id, unrelated songs and cover normalization (8 boundaries)");
  }
}
