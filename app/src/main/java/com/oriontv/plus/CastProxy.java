package com.oriontv.plus;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 投屏用的本地 HTTP 代理。
 * <p>
 * 很多电视的 DLNA 播放器只能拉 http 直链，且不带 Referer/UA，遇到 https、
 * 防盗链或跳转地址就会黑屏。这里把地址换成手机局域网内的 http 地址，
 * 由 App 代取内容并补上请求头；m3u8 里的分片 / 子清单也一并改写，
 * 保证电视拿到的每个地址都能直接访问。
 */
public final class CastProxy {

    private static final String UA = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36";
    /** 一段时间没有请求就释放 Wi-Fi / CPU 锁，避免一直占着 */
    private static final long IDLE_RELEASE_MS = 3 * 60 * 1000L;

    private static CastProxy instance;

    private final Context app;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> tokenByUrl = new ConcurrentHashMap<>();

    private ServerSocket server;
    private int port;
    private volatile long lastActive = System.currentTimeMillis();
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private int seq;

    private static final class Entry {
        final String url;
        final String referer;
        volatile long used = System.currentTimeMillis();

        Entry(String url, String referer) {
            this.url = url;
            this.referer = referer;
        }
    }

    private CastProxy(Context app) {
        this.app = app;
    }

    public static CastProxy get(Context ctx) {
        synchronized (CastProxy.class) {
            if (instance == null) {
                instance = new CastProxy(ctx.getApplicationContext());
            }
            return instance;
        }
    }

    /** 把远端地址登记到代理，返回电视可访问的局域网 http 地址；失败返回 null */
    public String proxyUrl(String url, String referer) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        ensureServer();
        if (server == null || server.isClosed()) {
            return null;
        }
        String host = CastManager.lanIp(app);
        if (host == null || host.isEmpty()) {
            return null;
        }
        String token = tokenByUrl.get(url);
        if (token == null) {
            token = Integer.toHexString(++seq) + "-" + Integer.toHexString(Math.abs(url.hashCode()));
            tokenByUrl.put(url, token);
        }
        entries.put(token, new Entry(url, referer));
        holdLocks();
        return "http://" + host + ":" + port + "/t/" + token;
    }

    /** 停止投屏后调用，尽快释放 Wi-Fi 锁 */
    public void release() {
        releaseLocks();
    }

    private synchronized void ensureServer() {
        if (server != null && !server.isClosed()) {
            return;
        }
        try {
            server = new ServerSocket(0);
            port = server.getLocalPort();
            android.util.Log.i("CastProxy", "listening on " + port);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "cast-proxy");
            t.setDaemon(true);
            t.start();
            Thread watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(30000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (System.currentTimeMillis() - lastActive > IDLE_RELEASE_MS) {
                            releaseLocks();
                        }
                    }
                }
            }, "cast-proxy-idle");
            watchdog.setDaemon(true);
            watchdog.start();
        } catch (Exception e) {
            server = null;
        }
    }

    private void acceptLoop() {
        while (true) {
            Socket s = null;
            try {
                s = server.accept();
                final Socket socket = s;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (Exception e) {
                if (server == null || server.isClosed()) {
                    return;
                }
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            InputStream raw = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(raw);
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            String range = null;
            String line;
            while ((line = readLine(raw)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i <= 0) {
                    continue;
                }
                String key = line.substring(0, i).trim().toLowerCase(Locale.US);
                String value = line.substring(i + 1).trim();
                if ("range".equals(key)) {
                    range = value;
                }
            }
            String token = path.startsWith("/t/") ? path.substring(3) : "";
            int q = token.indexOf('?');
            if (q >= 0) {
                token = token.substring(0, q);
            }
            Entry entry = entries.get(token);
            if (entry == null) {
                writeText(out, 404, "Not Found", "代理地址已失效");
                return;
            }
            entry.used = System.currentTimeMillis();
            lastActive = entry.used;
            relay(entry, method, range, out);
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 拉取远端内容并回写给电视，m3u8 会自动改写其中的地址 */
    private void relay(Entry entry, String method, String range, OutputStream out) {
        String current = entry.url;
        for (int hop = 0; hop <= 6; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "*/*");
                if (entry.referer != null && !entry.referer.isEmpty()) {
                    conn.setRequestProperty("Referer", entry.referer);
                }
                if (range != null) {
                    conn.setRequestProperty("Range", range);
                }
                if ("HEAD".equalsIgnoreCase(method)) {
                    conn.setRequestMethod("HEAD");
                }
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null || location.isEmpty()) {
                        writeText(out, 502, "Bad Gateway", "跳转地址缺失");
                        return;
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                String contentType = conn.getContentType();
                boolean playlist = looksPlaylist(current, contentType);
                boolean rangeFromStart = range == null
                        || range.replace(" ", "").startsWith("bytes=0-");
                if (!playlist && code >= 200 && code < 300 && rangeFromStart) {
                    // 无后缀的直播地址：先嗅探内容头部
                    InputStream probe = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (probe != null) {
                        byte[] head = new byte[1024];
                        int n = readFully(probe, head);
                        String text = new String(head, 0, Math.max(0, n), StandardCharsets.UTF_8);
                        if (text.contains("#EXTM3U")) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            bos.write(head, 0, Math.max(0, n));
                            copy(probe, bos, 4 * 1024 * 1024);
                            probe.close();
                            String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                            writePlaylist(out, rewritePlaylist(body, current, entry.referer));
                            conn.disconnect();
                            return;
                        }
                        writeStreamHead(out, code, contentType, conn);
                        out.write(head, 0, Math.max(0, n));
                        copy(probe, out, Long.MAX_VALUE);
                        probe.close();
                        conn.disconnect();
                        return;
                    }
                }
                if (playlist) {
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    if (in == null) {
                        writeText(out, 502, "Bad Gateway", "无法读取节目列表");
                        conn.disconnect();
                        return;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    copy(in, bos, 4 * 1024 * 1024);
                    in.close();
                    String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    if (!body.contains("#EXTM3U")) {
                        writeBytes(out, code, contentType, bos.toByteArray());
                    } else {
                        writePlaylist(out, rewritePlaylist(body, current, entry.referer));
                    }
                    conn.disconnect();
                    return;
                }
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (in == null) {
                    writeText(out, 502, "Bad Gateway", "无法读取视频内容");
                    conn.disconnect();
                    return;
                }
                writeStreamHead(out, code, contentType, conn);
                if (!"HEAD".equalsIgnoreCase(method)) {
                    copy(in, out, Long.MAX_VALUE);
                }
                in.close();
                conn.disconnect();
                return;
            } catch (Exception e) {
                if (conn != null) {
                    conn.disconnect();
                }
                writeText(out, 502, "Bad Gateway", "代理取流失败");
                return;
            }
        }
        writeText(out, 508, "Loop Detected", "跳转次数过多");
    }

    private static boolean looksPlaylist(String url, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (ct.contains("mpegurl") || ct.contains("x-mpegurl")) {
            return true;
        }
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        return u.endsWith(".m3u8") || u.endsWith(".m3u");
    }

    private String rewritePlaylist(String body, String baseUrl, String referer) {
        StringBuilder sb = new StringBuilder(body.length() + 256);
        String[] lines = body.split("\r?\n", -1);
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append('\n');
                continue;
            }
            if (t.startsWith("#")) {
                sb.append(rewriteUriAttrs(line, baseUrl, referer)).append('\n');
            } else {
                sb.append(rewriteOne(t, baseUrl, referer)).append('\n');
            }
        }
        return sb.toString();
    }

    private String rewriteUriAttrs(String line, String baseUrl, String referer) {
        int from = 0;
        StringBuilder sb = new StringBuilder(line.length() + 64);
        while (true) {
            int idx = line.indexOf("URI=\"", from);
            if (idx < 0) {
                sb.append(line.substring(from));
                break;
            }
            int end = line.indexOf('"', idx + 5);
            if (end < 0) {
                sb.append(line.substring(from));
                break;
            }
            sb.append(line, from, idx).append("URI=\"");
            sb.append(rewriteOne(line.substring(idx + 5, end), baseUrl, referer));
            sb.append('"');
            from = end + 1;
        }
        return sb.toString();
    }

    private String rewriteOne(String ref, String baseUrl, String referer) {
        try {
            String abs = new URL(new URL(baseUrl), ref).toString();
            String local = proxyUrl(abs, referer);
            return local != null ? local : abs;
        } catch (Exception e) {
            return ref;
        }
    }

    private static void writePlaylist(OutputStream out, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/vnd.apple.mpegurl\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static void writeStreamHead(OutputStream out, int code, String contentType,
                                       HttpURLConnection conn) throws Exception {
        String reason = code == 206 ? "Partial Content" : (code == 200 ? "OK" : "Error");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType == null ? "application/octet-stream" : contentType)
                .append("\r\n");
        String cr = conn.getHeaderField("Content-Range");
        if (cr != null) {
            sb.append("Content-Range: ").append(cr).append("\r\n");
        }
        int len = conn.getContentLength();
        if (len >= 0) {
            sb.append("Content-Length: ").append(len).append("\r\n");
        }
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void writeBytes(OutputStream out, int code, String contentType, byte[] data)
            throws Exception {
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + (contentType == null ? "application/octet-stream" : contentType) + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private static void writeText(OutputStream out, int code, String reason, String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws Exception {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n <= 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        while (total < limit) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, limit - total));
            if (n <= 0) {
                break;
            }
            out.write(buf, 0, n);
            out.flush();
            total += n;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
            if (bos.size() > 8192) {
                break;
            }
        }
        if (c < 0 && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    @SuppressWarnings("deprecation")
    private void holdLocks() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "juhaokan_cast_proxy");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "juhaokan:castproxy");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(30 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }
}
