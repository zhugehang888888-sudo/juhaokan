package com.oriontv.plus;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 线路（播放源）测速：参考网页端逻辑
 * 1. 请求 m3u8 记录延迟(ping)；
 * 2. 解析分辨率(RESOLUTION) 得到清晰度；
 * 3. 下载首个分片 512KB 计算实时速率。
 */
public class SourceTester {

    public static class Result {
        public String quality = "未知";
        public String speedText = "—";
        public double mbps = 0;
        public long pingMs = 0;
        public double score = 0;
        public boolean ok = false;
        public String error = "";
    }

    private static final Pattern RES = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");

    private static OkHttpClient client;

    private static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .callTimeout(14, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();
        }
        return client;
    }

    public static Result test(String url) {
        Result r = new Result();
        if (url == null || url.isEmpty()) {
            return r;
        }
        try {
            long t0 = System.currentTimeMillis();
            String playlist = fetchText(url);
            r.pingMs = System.currentTimeMillis() - t0;
            if (playlist == null || !playlist.contains("#EXTM3U")) {
                r.error = "无法访问";
                return r;
            }

            String target = url;
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                int best = -1;
                int bestBw = -1;
                String bestLine = null;
                String[] lines = playlist.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (!line.startsWith("#EXT-X-STREAM-INF")) {
                        continue;
                    }
                    Matcher bm = BANDWIDTH.matcher(line);
                    int bw = bm.find() ? parseInt(bm.group(1)) : 0;
                    Matcher rm = RES.matcher(line);
                    if (rm.find()) {
                        r.quality = qualityOf(parseInt(rm.group(2)));
                    }
                    for (int j = i + 1; j < lines.length; j++) {
                        String cand = lines[j].trim();
                        if (!cand.isEmpty() && !cand.startsWith("#")) {
                            if (bw > bestBw) {
                                bestBw = bw;
                                bestLine = cand;
                                best = j;
                            }
                            break;
                        }
                    }
                }
                if (bestLine != null) {
                    target = resolve(url, bestLine);
                    playlist = fetchText(target);
                    if (playlist == null) {
                        r.error = "分片列表获取失败";
                        return r;
                    }
                }
            }

            String seg = firstSegment(playlist);
            if (seg == null) {
                r.ok = true;
                r.speedText = "—";
                r.score = scoreOf(r.quality, 0, r.pingMs);
                return r;
            }
            String segUrl = resolve(target, seg);
            long len = 0;
            long t1 = System.currentTimeMillis();
            Request req = new Request.Builder().url(segUrl)
                    .header("Range", "bytes=0-524287")
                    .header("User-Agent", "Mozilla/5.0 (Android) OrionTVPlus")
                    .build();
            Response resp = client().newCall(req).execute();
            if (resp.body() != null) {
                InputStream in = resp.body().byteStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    len += n;
                    if (len >= 524288) {
                        break;
                    }
                }
                in.close();
            }
            resp.close();
            long dt = System.currentTimeMillis() - t1;
            if (dt > 0 && len > 0) {
                r.mbps = (len / 1024.0) / (dt / 1000.0) / 1024.0;
                r.speedText = r.mbps >= 1
                        ? String.format(java.util.Locale.US, "%.1f MB/s", r.mbps)
                        : String.format(java.util.Locale.US, "%.0f KB/s", r.mbps * 1024);
            }
            r.ok = true;
            r.score = scoreOf(r.quality, r.mbps, r.pingMs);
            return r;
        } catch (Exception e) {
            r.error = "测速失败";
            return r;
        }
    }

    private static String fetchText(String url) {
        try {
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android) OrionTVPlus")
                    .build();
            Response resp = client().newCall(req).execute();
            String s = resp.body() != null ? resp.body().string() : null;
            resp.close();
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstSegment(String playlist) {
        if (playlist == null) {
            return null;
        }
        String[] lines = playlist.split("\n");
        for (String line : lines) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                return s;
            }
        }
        return null;
    }

    private static String resolve(String base, String rel) {
        if (rel.startsWith("http://") || rel.startsWith("https://")) {
            return rel;
        }
        try {
            java.net.URL b = new java.net.URL(base);
            if (rel.startsWith("/")) {
                return b.getProtocol() + "://" + b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "") + rel;
            }
            String path = b.getPath();
            int idx = path.lastIndexOf('/');
            String dir = idx >= 0 ? path.substring(0, idx + 1) : "/";
            return b.getProtocol() + "://" + b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "") + dir + rel;
        } catch (Exception e) {
            return rel;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String qualityOf(int height) {
        if (height >= 2160) {
            return "4K";
        }
        if (height >= 1440) {
            return "2K";
        }
        if (height >= 1080) {
            return "1080p";
        }
        if (height >= 720) {
            return "720p";
        }
        if (height >= 480) {
            return "480p";
        }
        return "SD";
    }

    private static double scoreOf(String quality, double mbps, long pingMs) {
        double q;
        if ("4K".equals(quality)) {
            q = 100;
        } else if ("2K".equals(quality)) {
            q = 85;
        } else if ("1080p".equals(quality)) {
            q = 70;
        } else if ("720p".equals(quality)) {
            q = 50;
        } else if ("480p".equals(quality)) {
            q = 30;
        } else {
            q = 40;
        }
        double speedScore = Math.min(30, mbps * 3);
        double pingScore = Math.max(0, 20 - pingMs / 50.0);
        return q + speedScore + pingScore;
    }
}
