package com.oriontv.plus;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 直播源解析：自动识别 M3U / M3U8 播放列表与 TXT 频道列表。
 * <p>
 * M3U：
 * #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV1综合
 * http://xxx.m3u8
 * <p>
 * TXT：
 * 央视,#genre#
 * CCTV1综合,http://xxx.m3u8
 */
public class LiveParser {

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private static final String DEFAULT_GROUP = "未分组";

    private static OkHttpClient http;

    private static synchronized OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build();
        }
        return http;
    }

    public interface Cb {
        void done(List<LiveChannel> list, String err);
    }

    /** 后台线程拉取并解析，回调在主线程 */
    public static void load(final String sourceName, final String url, final Cb cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<LiveChannel> list = new ArrayList<>();
                String err = null;
                try {
                    String body = fetch(url);
                    list = parse(body);
                    for (LiveChannel c : list) {
                        c.sourceName = sourceName == null ? "" : sourceName;
                    }
                    if (list.isEmpty()) {
                        err = "订阅源里没有解析到频道";
                    }
                } catch (Exception e) {
                    err = "加载失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                final List<LiveChannel> result = list;
                final String error = err;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(result, error);
                    }
                });
            }
        }).start();
    }

    private static String fetch(String url) throws IOException {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        Request req = new Request.Builder()
                .url(u)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                .build();
        Response r = http().newCall(req).execute();
        try {
            if (!r.isSuccessful()) {
                throw new IOException("HTTP " + r.code());
            }
            byte[] bytes = r.body() == null ? new byte[0] : r.body().bytes();
            return decode(bytes);
        } finally {
            r.close();
        }
    }

    /** 先按 UTF-8 严格解码，失败再按 GBK 解一次（部分老 TXT 源是 GBK） */
    private static String decode(byte[] bytes) {
        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception ignored) {
                return new String(bytes);
            }
        }
    }

    /** 自动识别格式并解析 */
    public static List<LiveChannel> parse(String body) {
        List<LiveChannel> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        String text = body.replace("\r\n", "\n").replace('\r', '\n');
        if (text.toUpperCase(Locale.US).contains("#EXTM3U")
                || text.toUpperCase(Locale.US).contains("#EXTINF")) {
            parseM3u(text, out);
        } else {
            parseTxt(text, out);
        }
        return out;
    }

    private static void parseM3u(String text, List<LiveChannel> out) {
        String pendingName = null;
        String pendingGroup = DEFAULT_GROUP;
        String pendingLogo = "";
        String pendingExtraGroup = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase(Locale.US);
            if (upper.startsWith("#EXTINF")) {
                pendingGroup = DEFAULT_GROUP;
                pendingLogo = "";
                pendingExtraGroup = null;
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    String k = m.group(1).toLowerCase(Locale.US);
                    String v = m.group(2).trim();
                    if ("group-title".equals(k) && !v.isEmpty()) {
                        pendingGroup = v;
                    } else if ("tvg-logo".equals(k)) {
                        pendingLogo = v;
                    }
                }
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 && comma + 1 < line.length()
                        ? line.substring(comma + 1).trim() : "";
                continue;
            }
            if (upper.startsWith("#EXTGRP:")) {
                pendingExtraGroup = line.substring(8).trim();
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (pendingName == null && pendingExtraGroup == null) {
                continue;
            }
            String name = pendingName == null || pendingName.isEmpty()
                    ? "频道" + (out.size() + 1) : pendingName;
            LiveChannel c = new LiveChannel();
            c.name = name;
            c.group = pendingExtraGroup != null && !pendingExtraGroup.isEmpty()
                    ? pendingExtraGroup : pendingGroup;
            if (c.group == null || c.group.isEmpty()) {
                c.group = DEFAULT_GROUP;
            }
            c.logo = pendingLogo == null ? "" : pendingLogo;
            c.url = line;
            out.add(c);
            pendingName = null;
            pendingExtraGroup = null;
        }
    }

    private static void parseTxt(String text, List<LiveChannel> out) {
        String group = DEFAULT_GROUP;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("#") && !line.contains(",")) {
                continue;
            }
            int comma = line.indexOf(',');
            if (comma < 0) {
                continue;
            }
            String left = line.substring(0, comma).trim();
            String right = line.substring(comma + 1).trim();
            if (right.toUpperCase(Locale.US).startsWith("#GENRE#")) {
                group = left.isEmpty() ? DEFAULT_GROUP : left;
                continue;
            }
            if (!right.startsWith("http://") && !right.startsWith("https://")
                    && !right.startsWith("rtmp") && !right.startsWith("rtsp")
                    && !right.startsWith("udp") && !right.startsWith("rtp")) {
                continue;
            }
            LiveChannel c = new LiveChannel();
            c.name = left.isEmpty() ? "频道" + (out.size() + 1) : left;
            c.group = group;
            c.url = right;
            out.add(c);
        }
    }
}
