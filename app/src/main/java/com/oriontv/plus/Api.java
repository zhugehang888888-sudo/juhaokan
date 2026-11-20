package com.oriontv.plus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * TV PLUS (LibreTV/MoonTV 兼容) 服务端接口封装。
 */
public class Api {

    public interface Cb<T> {
        void done(T data, String err);
    }

    private static final String TAG = "Api";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Api(Context c) {
        prefs = App.get().prefs();
        http = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* 基础请求                                                            */
    /* ------------------------------------------------------------------ */

    private <T> void postUi(final Cb<T> cb, final T data, final String err) {
        if (cb == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.done(data, err);
            }
        });
    }

    private static String cookieFrom(Response r) {
        List<String> headers = r.headers("Set-Cookie");
        for (String h : headers) {
            String s = h.trim();
            if (s.startsWith("auth=")) {
                int i = s.indexOf(';');
                return i > 0 ? s.substring(0, i) : s;
            }
        }
        return null;
    }

    public void login(final String server, final String user, final String pass, final Cb<Boolean> cb) {
        prefs.setServer(server);
        prefs.setAccount(user, pass);
        JSONObject body = new JSONObject();
        try {
            body.put("username", user);
            body.put("password", pass);
        } catch (Exception ignored) {
        }
        Request req = new Request.Builder()
                .url(prefs.server() + "/api/login")
                .post(RequestBody.create(JSON_TYPE, body.toString()))
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postUi(cb, false, "连接失败：" + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    postUi(cb, false, "登录失败：HTTP " + response.code());
                    return;
                }
                boolean ok = text.contains("\"ok\":true") || text.contains("\"ok\": true");
                String cookie = cookieFrom(response);
                if (cookie != null && !cookie.isEmpty()) {
                    prefs.setCookie(cookie);
                }
                prefs.setConfigured(ok && !prefs.cookie().isEmpty());
                postUi(cb, ok, ok ? null : "账号或密码错误");
            }
        });
    }

    /** 同步登录，供 401 自动续期使用 */
    private boolean loginBlocking() {
        JSONObject body = new JSONObject();
        try {
            body.put("username", prefs.user());
            body.put("password", prefs.pass());
        } catch (Exception ignored) {
        }
        Request req = new Request.Builder()
                .url(prefs.server() + "/api/login")
                .post(RequestBody.create(JSON_TYPE, body.toString()))
                .build();
        try {
            Response r = http.newCall(req).execute();
            String txt = r.body() != null ? r.body().string() : "";
            String cookie = cookieFrom(r);
            if (cookie != null) {
                prefs.setCookie(cookie);
            }
            return r.isSuccessful() && (txt.contains("\"ok\":true") || !prefs.cookie().isEmpty());
        } catch (Exception e) {
            Log.w(TAG, "relogin failed", e);
            return false;
        }
    }

    public interface RawCb {
        void done(String body, String err);
    }

    /** 是否已有登录态（cookie） */
    public boolean authed() {
        return prefs != null && !prefs.cookie().isEmpty();
    }

    private void authed(final String path, final String method, final JSONObject body,
                        final RawCb cb, final boolean retried) {
        Request.Builder b = new Request.Builder()
                .url(prefs.server() + path)
                .header("Cookie", prefs.cookie())
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "OrionTVPlus/1.0 (Android TV)");
        if ("POST".equals(method)) {
            b.post(RequestBody.create(JSON_TYPE, body == null ? "{}" : body.toString()));
        } else if ("DELETE".equals(method)) {
            b.delete();
        }
        http.newCall(b.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (cb != null) {
                    cb.done(null, "网络错误：" + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() != null ? response.body().string() : "";
                if (response.code() == 401 && !retried) {
                    if (loginBlocking()) {
                        authed(path, method, body, cb, true);
                        return;
                    }
                    if (cb != null) {
                        cb.done(null, "登录已失效，请到「设置」重新登录");
                    }
                    return;
                }
                if (!response.isSuccessful()) {
                    if (cb != null) {
                        cb.done(null, "请求失败：HTTP " + response.code());
                    }
                    return;
                }
                String cookie = cookieFrom(response);
                if (cookie != null) {
                    prefs.setCookie(cookie);
                }
                if (cb != null) {
                    cb.done(text, null);
                }
            }
        });
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* 业务接口                                                            */
    /* ------------------------------------------------------------------ */

    public void ping(final Cb<String> cb) {
        authed("/api/search?q=" + enc("测试"), "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                postUi(cb, err == null ? "连接正常" : null, err);
            }
        }, false);
    }

    public void search(String q, final Cb<List<VideoItem>> cb) {
        authed("/api/search?q=" + enc(q), "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                try {
                    JSONObject o = new JSONObject(body);
                    postUi(cb, VideoItem.listFrom(o.optJSONArray("results")), null);
                } catch (Exception e) {
                    postUi(cb, null, "数据解析失败");
                }
            }
        }, false);
    }

    public void douban(String type, String tag, final Cb<List<VideoItem>> cb) {
        douban(type, tag, 0, cb);
    }

    public void douban(String type, String tag, int start, final Cb<List<VideoItem>> cb) {
        String path = "/api/douban?type=" + enc(type) + "&tag=" + enc(tag)
                + "&pageSize=25&pageStart=" + start;
        authed(path, "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                postUi(cb, parseSimpleList(body), null);
            }
        }, false);
    }

    /**
     * 豆瓣推荐（网页版「分类 + 筛选」使用的同源接口）。
     * 参数为空字符串表示不限；sort: T 综合 / U 近期热度 / S 高分优先。
     */
    public void recommends(String kind, String category, String format, String region,
                           String year, String platform, String sort, String label,
                           int start, final Cb<List<VideoItem>> cb) {
        String path = "/api/douban/recommends?kind=" + enc(kind)
                + "&limit=25&start=" + start
                + "&category=" + enc(category)
                + "&format=" + enc(format)
                + "&region=" + enc(region)
                + "&year=" + enc(year)
                + "&platform=" + enc(platform)
                + "&sort=" + enc(sort)
                + "&label=" + enc(label);
        authed(path, "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                postUi(cb, parseSimpleList(body), null);
            }
        }, false);
    }

    /** Bangumi 每日放送（按星期取值 Mon..Sun） */
    public void bangumiCalendar(String weekdayEn, final Cb<List<VideoItem>> cb) {
        authed("/api/bangumi/calendar", "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                List<VideoItem> list = new ArrayList<>();
                try {
                    JSONArray days = new JSONArray(body);
                    for (int i = 0; i < days.length(); i++) {
                        JSONObject day = days.optJSONObject(i);
                        if (day == null) {
                            continue;
                        }
                        JSONObject wd = day.optJSONObject("weekday");
                        String en = wd == null ? "" : wd.optString("en", "");
                        if (!weekdayEn.equalsIgnoreCase(en)) {
                            continue;
                        }
                        JSONArray items = day.optJSONArray("items");
                        if (items == null) {
                            break;
                        }
                        for (int k = 0; k < items.length(); k++) {
                            JSONObject it = items.optJSONObject(k);
                            if (it == null) {
                                continue;
                            }
                            JSONObject images = it.optJSONObject("images");
                            if (images == null) {
                                continue;
                            }
                            VideoItem v = new VideoItem();
                            v.id = it.optString("id", "");
                            v.doubanId = it.optLong("id", 0);
                            v.title = it.optString("name_cn", "");
                            if (v.title.isEmpty()) {
                                v.title = it.optString("name", "");
                            }
                            v.poster = images.optString("large", images.optString("common", ""));
                            JSONObject rating = it.optJSONObject("rating");
                            double score = rating == null ? 0 : rating.optDouble("score", 0);
                            if (score > 0) {
                                v.rate = String.format(Locale.US, "%.1f", score);
                            }
                            String air = it.optString("air_date", "");
                            if (air.length() >= 4) {
                                v.year = air.substring(0, 4);
                            }
                            list.add(v);
                        }
                        break;
                    }
                } catch (Exception ignored) {
                }
                postUi(cb, list, null);
            }
        }, false);
    }

    private static List<VideoItem> parseSimpleList(String body) {
        List<VideoItem> list = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("list");
            if (arr == null) {
                return list;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                VideoItem v = new VideoItem();
                v.id = it.optString("id", "");
                v.doubanId = it.optLong("id", 0);
                v.title = it.optString("title", "");
                v.poster = it.optString("poster", "");
                v.rate = it.optString("rate", "");
                v.year = it.optString("year", "");
                list.add(v);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public void detail(String source, String id, final Cb<VideoItem> cb) {
        String path = "/api/detail?source=" + enc(source) + "&id=" + enc(id);
        authed(path, "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                try {
                    postUi(cb, VideoItem.from(new JSONObject(body)), null);
                } catch (Exception e) {
                    postUi(cb, null, "数据解析失败");
                }
            }
        }, false);
    }

    public void playRecords(final Cb<JSONObject> cb) {
        authed("/api/playrecords", "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                try {
                    postUi(cb, new JSONObject(body), null);
                } catch (Exception e) {
                    postUi(cb, new JSONObject(), null);
                }
            }
        }, false);
    }

    public void savePlayRecord(String key, JSONObject record) {
        JSONObject body = new JSONObject();
        try {
            body.put("key", key);
            body.put("record", record);
        } catch (Exception ignored) {
        }
        authed("/api/playrecords", "POST", body, null, false);
    }

    public void clearPlayRecords() {
        authed("/api/playrecords", "DELETE", new JSONObject(), null, false);
    }

    public void skipConfigs(final Cb<JSONObject> cb) {
        authed("/api/skipconfigs", "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                try {
                    postUi(cb, new JSONObject(body), null);
                } catch (Exception e) {
                    postUi(cb, new JSONObject(), null);
                }
            }
        }, false);
    }

    public void saveSkipConfig(String key, JSONObject config) {
        JSONObject body = new JSONObject();
        try {
            body.put("key", key);
            body.put("config", config);
        } catch (Exception ignored) {
        }
        authed("/api/skipconfigs", "POST", body, null, false);
    }

    public void favorites(final Cb<JSONObject> cb) {
        authed("/api/favorites", "GET", null, new RawCb() {
            @Override
            public void done(String body, String err) {
                if (err != null) {
                    postUi(cb, null, err);
                    return;
                }
                try {
                    postUi(cb, new JSONObject(body), null);
                } catch (Exception e) {
                    postUi(cb, new JSONObject(), null);
                }
            }
        }, false);
    }

    public void addFavorite(String key, JSONObject favorite) {
        JSONObject body = new JSONObject();
        try {
            body.put("key", key);
            body.put("favorite", favorite);
        } catch (Exception ignored) {
        }
        authed("/api/favorites", "POST", body, null, false);
    }

    public void removeFavorite(String key) {
        authed("/api/favorites?key=" + enc(key), "DELETE", new JSONObject(), null, false);
    }
}
