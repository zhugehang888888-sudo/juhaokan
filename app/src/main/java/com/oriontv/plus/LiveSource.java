package com.oriontv.plus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 直播订阅源：名称 + 地址（支持 m3u / txt） */
public class LiveSource {

    public String name = "";
    public String url = "";

    public LiveSource() {
    }

    public LiveSource(String name, String url) {
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
    }

    public static List<LiveSource> list(Prefs prefs) {
        List<LiveSource> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.liveSources());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String u = o.optString("url", "");
                if (u.trim().isEmpty()) {
                    continue;
                }
                out.add(new LiveSource(o.optString("name", ""), u));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Prefs prefs, List<LiveSource> list) {
        JSONArray arr = new JSONArray();
        for (LiveSource s : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", s.name);
                o.put("url", s.url);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        prefs.setLiveSources(arr.toString());
    }

    /** 没填名称时用地址里的 host 做名字 */
    public static String guessName(String url) {
        try {
            String u = url.trim();
            int i = u.indexOf("://");
            if (i > 0) {
                u = u.substring(i + 3);
            }
            int j = u.indexOf('/');
            if (j > 0) {
                u = u.substring(0, j);
            }
            return u.isEmpty() ? "直播源" : u;
        } catch (Exception e) {
            return "直播源";
        }
    }
}
