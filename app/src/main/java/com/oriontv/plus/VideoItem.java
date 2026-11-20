package com.oriontv.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class VideoItem {

    public String id = "";
    public String title = "";
    public String poster = "";
    public String year = "";
    public String source = "";
    public String sourceName = "";
    public String typeName = "";
    public String desc = "";
    public String rate = "";
    public long doubanId = 0;
    public final List<String> episodes = new ArrayList<>();
    public final List<String> episodeTitles = new ArrayList<>();

    public boolean playable() {
        return !source.isEmpty() && !id.isEmpty() && !episodes.isEmpty();
    }

    public String key() {
        return source + "+" + id;
    }

    public static VideoItem from(JSONObject o) {
        VideoItem v = new VideoItem();
        if (o == null) {
            return v;
        }
        v.id = o.optString("id", "");
        v.title = o.optString("title", "");
        v.poster = o.optString("poster", "");
        v.year = o.optString("year", "");
        v.source = o.optString("source", "");
        v.sourceName = o.optString("source_name", "");
        v.typeName = o.optString("type_name", "");
        v.desc = o.optString("desc", "");
        v.rate = o.optString("rate", "");
        v.doubanId = o.optLong("douban_id", 0);
        JSONArray eps = o.optJSONArray("episodes");
        if (eps != null) {
            for (int i = 0; i < eps.length(); i++) {
                String s = eps.optString(i, "");
                if (!s.isEmpty()) {
                    v.episodes.add(s);
                }
            }
        }
        JSONArray titles = o.optJSONArray("episodes_titles");
        if (titles != null) {
            for (int i = 0; i < titles.length(); i++) {
                v.episodeTitles.add(titles.optString(i, ""));
            }
        }
        // 顺手记入本地片名索引，供拼音首字母搜索还原中文名
        TitleIndex.remember(v.title);
        return v;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title);
            o.put("poster", poster);
            o.put("year", year);
            o.put("source", source);
            o.put("source_name", sourceName);
            o.put("type_name", typeName);
            o.put("desc", desc);
            o.put("rate", rate);
            o.put("douban_id", doubanId);
            o.put("episodes", new JSONArray(episodes));
            o.put("episodes_titles", new JSONArray(episodeTitles));
        } catch (Exception ignored) {
        }
        return o;
    }

    public String episodeLabel(int index) {
        if (index >= 0 && index < episodeTitles.size()) {
            String t = episodeTitles.get(index);
            if (t != null && !t.isEmpty()) {
                return t;
            }
        }
        return "第" + (index + 1) + "集";
    }

    public static List<VideoItem> listFrom(JSONArray arr) {
        List<VideoItem> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                list.add(from(o));
            }
        }
        return list;
    }
}
