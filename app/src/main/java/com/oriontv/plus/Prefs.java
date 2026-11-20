package com.oriontv.plus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Prefs {

    private static final String FILE = "oriontv_plus";

    private static final String DEFAULT_SERVER = BuildConfig.SERVER_URL;
    private static final String DEFAULT_USER = BuildConfig.SERVER_USER;
    private static final String DEFAULT_PASS = BuildConfig.SERVER_PASS;

    private static final String KEY_SERVERS = "servers";
    private static final String KEY_ACTIVE = "serveractive";
    private static final String KEY_MIGRATED = "servers_v1";

    /** 直播订阅源按账号隔离存储：livesources_<账号id> / livedefault_<账号id> */
    private static final String KEY_LIVE_SOURCES = "livesources";
    private static final String KEY_LIVE_DEFAULT = "livedefault";
    private static final String KEY_LIVE_SCOPED = "livescope_v1";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /* ------------------------------------------------------------------ */
    /* 多服务器账号                                                        */
    /* ------------------------------------------------------------------ */

    public synchronized List<ServerProfile> servers() {
        List<ServerProfile> list = new ArrayList<>();
        String raw = sp.getString(KEY_SERVERS, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(ServerProfile.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty() && !sp.getBoolean(KEY_MIGRATED, false)) {
            list = migrateServers();
        }
        return list;
    }

    /** 把旧版的单服务器配置迁移成一条账号记录 */
    private List<ServerProfile> migrateServers() {
        List<ServerProfile> list = new ArrayList<>();
        String activeId = "";
        if (BuildConfig.BUILT_IN_SERVER) {
            ServerProfile p = new ServerProfile();
            p.id = "builtin";
            p.name = "默认";
            p.url = DEFAULT_SERVER;
            p.user = DEFAULT_USER;
            p.pass = DEFAULT_PASS;
            p.cookie = sp.getString("cookie", "");
            p.builtin = true;
            list.add(p);
            activeId = p.id;
        } else {
            String old = sp.getString("server", "");
            if (old != null && !old.trim().isEmpty()) {
                ServerProfile p = new ServerProfile();
                p.id = "s" + System.currentTimeMillis();
                p.name = "我的服务器";
                p.url = trimSlash(old);
                p.user = sp.getString("user", "");
                p.pass = sp.getString("pass", "");
                p.cookie = sp.getString("cookie", "");
                list.add(p);
                activeId = p.id;
            }
        }
        saveServers(list);
        sp.edit().putBoolean(KEY_MIGRATED, true).putString(KEY_ACTIVE, activeId).apply();
        return list;
    }

    public synchronized void saveServers(List<ServerProfile> list) {
        JSONArray arr = new JSONArray();
        for (ServerProfile p : list) {
            arr.put(p.toJson());
        }
        sp.edit().putString(KEY_SERVERS, arr.toString()).apply();
    }

    public synchronized ServerProfile activeServer() {
        List<ServerProfile> list = servers();
        String id = sp.getString(KEY_ACTIVE, "");
        for (ServerProfile p : list) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    public void setActiveServer(String id) {
        sp.edit().putString(KEY_ACTIVE, id == null ? "" : id).apply();
    }

    /** 当前生效的账号：没有账号或账号已停用时返回 null（此时不读取任何订阅源） */
    private ServerProfile liveOwner() {
        ServerProfile p = activeServer();
        return p == null || p.disabled ? null : p;
    }

    /** 当前生效账号的 id，没有则为空串；用于判断账号是否发生切换 */
    public String activeServerId() {
        ServerProfile p = liveOwner();
        return p == null ? "" : p.id;
    }

    /** 订阅源存储键带账号后缀，保证不同服务器的直播源互不可见 */
    private String scopedKey(String base) {
        String id = activeServerId();
        return id.isEmpty() ? "" : base + "_" + id;
    }

    private String readScoped(String base, String legacyKey) {
        String key = scopedKey(base);
        if (key.isEmpty()) {
            return "";
        }
        if (sp.contains(key)) {
            return sp.getString(key, "");
        }
        // 老版本是全局单份数据：升级后归入首次访问到的账号，避免跨账号串数据
        if (!sp.getBoolean(KEY_LIVE_SCOPED, false)) {
            String legacy = sp.getString(legacyKey, "");
            if (KEY_LIVE_SOURCES.equals(base) && !isBuiltinOwner()) {
                // 归入非内置账号时，剔除内置预置源，避免内置源跟着账号跑到别的服务器
                legacy = stripBuiltinSources(legacy);
            }
            sp.edit().putString(key, legacy).putBoolean(KEY_LIVE_SCOPED, true).apply();
            return legacy;
        }
        return "";
    }

    private boolean isBuiltinOwner() {
        ServerProfile p = liveOwner();
        return p != null && p.builtin;
    }

    /** 从订阅源 JSON 中剔除内置预置源（按地址匹配） */
    private String stripBuiltinSources(String json) {
        if (json == null || json.isEmpty() || BuildConfig.LIVE_SOURCES == null
                || BuildConfig.LIVE_SOURCES.isEmpty()) {
            return json == null ? "" : json;
        }
        Set<String> builtinUrls = new HashSet<>();
        for (String part : BuildConfig.LIVE_SOURCES.split(";")) {
            int cut = part.indexOf('|');
            if (cut > 0) {
                builtinUrls.add(part.substring(cut + 1).trim());
            }
        }
        try {
            JSONArray arr = new JSONArray(json);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !builtinUrls.contains(o.optString("url", "").trim())) {
                    out.put(o);
                }
            }
            return out.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private void writeScoped(String base, String v) {
        String key = scopedKey(base);
        if (key.isEmpty()) {
            return;
        }
        sp.edit().putString(key, v == null ? "" : v).putBoolean(KEY_LIVE_SCOPED, true).apply();
    }

    public synchronized void upsertServer(ServerProfile target) {
        List<ServerProfile> list = servers();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(target.id)) {
                list.set(i, target);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(target);
        }
        saveServers(list);
    }

    public synchronized void removeServer(String id) {
        List<ServerProfile> list = servers();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id) && !list.get(i).builtin) {
                list.remove(i);
                break;
            }
        }
        saveServers(list);
        if (id != null && !id.isEmpty()) {
            // 账号被删除，配套的直播源一并清除，避免残留数据再被其它账号读到
            sp.edit().remove(KEY_LIVE_SOURCES + "_" + id)
                    .remove(KEY_LIVE_DEFAULT + "_" + id).apply();
        }
        if (id != null && id.equals(sp.getString(KEY_ACTIVE, ""))) {
            setActiveServer(list.isEmpty() ? "" : list.get(0).id);
        }
    }

    /** 没有可用账号时创建一个手动账号占位 */
    private ServerProfile ensureServer() {
        ServerProfile p = activeServer();
        if (p != null) {
            return p;
        }
        p = new ServerProfile();
        p.id = "s" + System.currentTimeMillis();
        p.name = "我的服务器";
        upsertServer(p);
        setActiveServer(p.id);
        return p;
    }

    public String server() {
        ServerProfile p = activeServer();
        if (p == null || p.disabled) {
            return "";
        }
        String s = trimSlash(p.url);
        if (s.isEmpty() && p.builtin) {
            s = trimSlash(DEFAULT_SERVER);
        }
        return s;
    }

    public void setServer(String v) {
        ServerProfile p = ensureServer();
        p.url = trimSlash(v);
        upsertServer(p);
    }

    public String user() {
        ServerProfile p = activeServer();
        if (p == null) {
            return "";
        }
        if (p.user.isEmpty() && p.builtin) {
            return DEFAULT_USER;
        }
        return p.user;
    }

    public String pass() {
        ServerProfile p = activeServer();
        if (p == null) {
            return "";
        }
        if (p.pass.isEmpty() && p.builtin) {
            return DEFAULT_PASS;
        }
        return p.pass;
    }

    public void setAccount(String u, String p) {
        ServerProfile s = ensureServer();
        s.user = u == null ? "" : u;
        s.pass = p == null ? "" : p;
        upsertServer(s);
    }

    public String cookie() {
        ServerProfile p = activeServer();
        return p == null ? "" : p.cookie;
    }

    public void setCookie(String v) {
        ServerProfile p = ensureServer();
        p.cookie = v == null ? "" : v;
        upsertServer(p);
    }

    public boolean configured() {
        ServerProfile p = activeServer();
        if (p == null || p.disabled) {
            return false;
        }
        if (p.builtin) {
            return true;
        }
        return !p.cookie.isEmpty();
    }

    public void setConfigured(boolean v) {
        sp.edit().putBoolean("configured", v).apply();
    }

    public boolean adBlock() {
        return sp.getBoolean("adblock", true);
    }

    public void setAdBlock(boolean v) {
        sp.edit().putBoolean("adblock", v).apply();
    }

    public float speed() {
        return sp.getFloat("speed", 1.0f);
    }

    public void setSpeed(float v) {
        sp.edit().putFloat("speed", v).apply();
    }

    public boolean autoNext() {
        return sp.getBoolean("autonext", true);
    }

    public void setAutoNext(boolean v) {
        sp.edit().putBoolean("autonext", v).apply();
    }

    public boolean skipEnabled() {
        return sp.getBoolean("skip", true);
    }

    public void setSkipEnabled(boolean v) {
        sp.edit().putBoolean("skip", v).apply();
    }

    /** 本机缓存的跳过片头片尾配置表（JSON），key 为 片源+影片ID */
    public String skipConfigs() {
        return sp.getString("skipconfigs", "");
    }

    public void setSkipConfigs(String v) {
        sp.edit().putString("skipconfigs", v == null ? "" : v).apply();
    }

    public boolean clockEnabled() {
        return sp.getBoolean("clock", true);
    }

    public void setClockEnabled(boolean v) {
        sp.edit().putBoolean("clock", v).apply();
    }

    public int resizeMode() {
        return sp.getInt("resize", 0);
    }

    public void setResizeMode(int v) {
        sp.edit().putInt("resize", v).apply();
    }

    /** 直播订阅源（JSON 数组：[{name,url}]），按当前账号隔离 */
    public String liveSources() {
        return readScoped(KEY_LIVE_SOURCES, "livesources");
    }

    public void setLiveSources(String v) {
        writeScoped(KEY_LIVE_SOURCES, v);
    }

    /** 首页默认内容为直播 */
    public boolean homeLive() {
        return sp.getBoolean("homelive", false);
    }

    public void setHomeLive(boolean v) {
        sp.edit().putBoolean("homelive", v).apply();
    }

    /** 上次观看的直播频道序号（1 起） */
    public int liveLastIndex() {
        return sp.getInt("liveindex", 1);
    }

    public void setLiveLastIndex(int v) {
        sp.edit().putInt("liveindex", Math.max(1, v)).apply();
    }

    /** 默认直播订阅源地址（空表示用第一个订阅源），按当前账号隔离 */
    public String liveDefaultSource() {
        return readScoped(KEY_LIVE_DEFAULT, "livedefault");
    }

    public void setLiveDefaultSource(String v) {
        writeScoped(KEY_LIVE_DEFAULT, v);
    }

    /** 直播解码方式：0 自动 / 1 硬解 / 2 软解 */
    public int liveDecode() {
        return sp.getInt("livedecode", 0);
    }

    public void setLiveDecode(int v) {
        sp.edit().putInt("livedecode", v).apply();
    }

    /** 直播播放内核：0 Media3 / 1 系统播放器 */
    public int liveKernel() {
        return sp.getInt("livekernel", 0);
    }

    public void setLiveKernel(int v) {
        sp.edit().putInt("livekernel", v).apply();
    }

    /** 开机自动启动 */
    public boolean bootAutoStart() {
        return sp.getBoolean("bootauto", false);
    }

    public void setBootAutoStart(boolean v) {
        sp.edit().putBoolean("bootauto", v).apply();
    }

    public void putString(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    public String getString(String k, String def) {
        return sp.getString(k, def);
    }
}
