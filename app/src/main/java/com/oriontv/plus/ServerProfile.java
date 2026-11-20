package com.oriontv.plus;

import org.json.JSONException;
import org.json.JSONObject;

/** 一个可保存的服务器账号 */
public class ServerProfile {

    public String id = "";
    public String name = "";
    public String url = "";
    public String user = "";
    public String pass = "";
    public String cookie = "";
    /** 内置版自带的默认服务器，不可编辑/删除，可停用 */
    public boolean builtin = false;
    public boolean disabled = false;

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (builtin) {
            return "默认";
        }
        return url == null || url.isEmpty() ? "未命名服务器" : url;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("user", user);
            o.put("pass", pass);
            o.put("cookie", cookie);
            o.put("builtin", builtin);
            o.put("disabled", disabled);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static ServerProfile fromJson(JSONObject o) {
        ServerProfile p = new ServerProfile();
        p.id = o.optString("id", "");
        p.name = o.optString("name", "");
        p.url = o.optString("url", "");
        p.user = o.optString("user", "");
        p.pass = o.optString("pass", "");
        p.cookie = o.optString("cookie", "");
        p.builtin = o.optBoolean("builtin", false);
        p.disabled = o.optBoolean("disabled", false);
        return p;
    }
}
