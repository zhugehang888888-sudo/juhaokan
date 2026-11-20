package com.oriontv.plus;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地片名索引：记住 App 见过的片名及其拼音首字母。
 * 纯字母搜索时先用它把 jsxs 还原成候选中文名，再用中文名去服务端聚合各源，
 * 避免服务端对首字母支持不一致导致的“搜不到 / 胡乱匹配”。
 */
public final class TitleIndex {

    private static final String PREF_FILE = "oriontv_plus";
    private static final String KEY = "titleindex_v1";
    private static final int MAX = 1500;

    private static final LinkedHashMap<String, String> MAP = new LinkedHashMap<>();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable FLUSH = new Runnable() {
        @Override
        public void run() {
            flush();
        }
    };
    private static boolean loaded;
    private static boolean dirty;
    private static SharedPreferences sp;

    private TitleIndex() {
    }

    /** 记录一个片名（只记新片名，重复调用开销很小） */
    public static void remember(String title) {
        if (title == null) {
            return;
        }
        String t = title.trim();
        if (t.isEmpty() || t.length() > 60) {
            return;
        }
        synchronized (TitleIndex.class) {
            ensureLoaded();
            if (MAP.containsKey(t)) {
                return;
            }
            MAP.put(t, Pinyin.initials(t));
            while (MAP.size() > MAX) {
                Iterator<Map.Entry<String, String>> it = MAP.entrySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
            dirty = true;
            HANDLER.removeCallbacks(FLUSH);
            HANDLER.postDelayed(FLUSH, 1500);
        }
    }

    /** 按拼音首字母找候选片名：完全相等 > 前缀 > 包含 */
    public static List<String> match(String key, int limit) {
        List<String> exact = new ArrayList<>();
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        if (key != null && !key.isEmpty()) {
            String k = key.toLowerCase(Locale.US);
            synchronized (TitleIndex.class) {
                ensureLoaded();
                for (Map.Entry<String, String> e : MAP.entrySet()) {
                    String ini = e.getValue();
                    if (ini == null || ini.isEmpty()) {
                        continue;
                    }
                    if (ini.equals(k)) {
                        exact.add(e.getKey());
                    } else if (ini.startsWith(k)) {
                        prefix.add(e.getKey());
                    } else if (ini.contains(k)) {
                        contains.add(e.getKey());
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        List<List<String>> groups = new ArrayList<>();
        groups.add(exact);
        groups.add(prefix);
        groups.add(contains);
        for (List<String> group : groups) {
            for (String t : group) {
                if (out.size() >= limit) {
                    return out;
                }
                out.add(t);
            }
        }
        return out;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (App.get() == null) {
                return;
            }
            sp = App.get().getSharedPreferences(PREF_FILE, 0);
            String raw = sp.getString(KEY, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray e = arr.optJSONArray(i);
                if (e == null || e.length() < 2) {
                    continue;
                }
                String title = e.optString(0, "");
                String ini = e.optString(1, "");
                if (!title.isEmpty() && !ini.isEmpty()) {
                    MAP.put(title, ini);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void flush() {
        synchronized (TitleIndex.class) {
            if (!dirty || sp == null) {
                return;
            }
            try {
                JSONArray arr = new JSONArray();
                for (Map.Entry<String, String> e : MAP.entrySet()) {
                    JSONArray one = new JSONArray();
                    one.put(e.getKey());
                    one.put(e.getValue());
                    arr.put(one);
                }
                sp.edit().putString(KEY, arr.toString()).apply();
                dirty = false;
            } catch (Exception ignored) {
            }
        }
    }
}
