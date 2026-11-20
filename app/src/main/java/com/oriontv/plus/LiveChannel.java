package com.oriontv.plus;

import java.util.ArrayList;
import java.util.List;

/** 直播频道（解析后带着全局序号，同名频道合并多个播放地址） */
public class LiveChannel {

    public int number = 0;
    public String name = "";
    /** 当前正在播放的地址，取自 urls */
    public String url = "";
    public String logo = "";
    public String group = "";
    public String sourceName = "";
    /** 同名频道的全部播放地址，切换源时按顺序尝试 */
    public final List<String> urls = new ArrayList<>();

    public LiveChannel() {
    }

    public void addUrl(String u) {
        if (u == null) {
            return;
        }
        String s = u.trim();
        if (s.isEmpty() || urls.contains(s)) {
            return;
        }
        urls.add(s);
        if (url == null || url.isEmpty()) {
            url = s;
        }
    }

    public String urlAt(int index) {
        if (urls.isEmpty()) {
            return url == null ? "" : url;
        }
        int i = Math.max(0, Math.min(index, urls.size() - 1));
        return urls.get(i);
    }
}
