package com.oriontv.plus;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 局域网 DLNA 投屏：SSDP 搜索媒体渲染器，解析设备描述，
 * 再通过 AVTransport 的 SetAVTransportURI + Play 让电视播放视频地址。
 * <p>
 * 兼容性处理：
 * 1. 同时用 MediaRenderer / AVTransport / ssdp:all / upnp:rootdevice 四种 ST 搜索，
 *    很多电视（含小米）只回应后两种；
 * 2. 绑定 Wi-Fi 网卡发多播，避免多网卡/加速器把包发到错误的接口；
 * 3. 按实际内容设置 DIDL 的 protocolInfo（HLS 用 application/vnd.apple.mpegurl），
 *    失败时降级重试；
 * 4. https / 防盗链 / 跳转地址自动走 App 内代理，电视只拿 http 直链。
 */
public class CastManager {

    public static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String UA_DLNA = "OrionTVPlus/1.0 UPnP/1.1 DLNADOC/1.50";

    public static class Device {
        public String name = "电视";
        public String location = "";
        public String controlUrl = "";
    }

    public interface DeviceCb {
        void done(List<Device> devices);
    }

    public interface ActionCb {
        void done(boolean ok, String msg);
    }

    private static volatile Device current;
    private static volatile String currentUrl = "";
    /** 最近一次扫描在局域网看到的 SSDP 响应数（用于区分“没设备”和“有设备但不支持投屏”） */
    public static volatile int lastSeen;

    public static Device current() {
        return current;
    }

    public static String currentName() {
        return current == null ? "" : current.name;
    }

    public static String currentUrl() {
        return currentUrl;
    }

    /* ------------------------------------------------------------------ */
    /* 网络地址                                                            */
    /* ------------------------------------------------------------------ */

    /** 当前局域网 IPv4（优先 Wi-Fi / 有线），找不到返回 null */
    public static String lanIp(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                String fallback = null;
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (caps == null || lp == null) {
                        continue;
                    }
                    boolean wired = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) {
                            continue;
                        }
                        String ip = a.getHostAddress();
                        if (wired) {
                            return ip;
                        }
                        if (fallback == null) {
                            fallback = ip;
                        }
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                            + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* 搜索设备                                                            */
    /* ------------------------------------------------------------------ */

    public static void discover(final Context ctx, final DeviceCb cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Device> result = new ArrayList<>();
                lastSeen = 0;
                WifiManager.MulticastLock lock = null;
                DatagramSocket socket = null;
                try {
                    WifiManager wm = (WifiManager) ctx.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        lock = wm.createMulticastLock("juhaokan_cast");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    }
                    String localIp = lanIp(ctx);
                    if (localIp != null) {
                        socket = new DatagramSocket(0, InetAddress.getByName(localIp));
                    } else {
                        socket = new DatagramSocket();
                    }
                    socket.setBroadcast(true);

                    String[] targets = new String[]{
                            "urn:schemas-upnp-org:device:MediaRenderer:1",
                            "urn:schemas-upnp-org:service:AVTransport:1",
                            "upnp:rootdevice",
                            "ssdp:all"
                    };
                    InetAddress group = InetAddress.getByName("239.255.255.250");
                    for (String st : targets) {
                        byte[] data = msearch(st).getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(data, data.length, group, 1900));
                        try {
                            Thread.sleep(90);
                        } catch (InterruptedException ignored) {
                        }
                    }

                    // 收集响应：LOCATION -> 响应来源 IP
                    Map<String, String> locations = new LinkedHashMap<>();
                    byte[] buf = new byte[8192];
                    long deadline = System.currentTimeMillis() + 3600;
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            int left = (int) Math.max(250, deadline - System.currentTimeMillis());
                            socket.setSoTimeout(left);
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            socket.receive(p);
                            String text = new String(p.getData(), 0, p.getLength(),
                                    StandardCharsets.UTF_8);
                            if (!text.toUpperCase(Locale.US).startsWith("HTTP/1.1 200")) {
                                continue;
                            }
                            String location = header(text, "LOCATION");
                            if (location == null || location.isEmpty()) {
                                continue;
                            }
                            String from = p.getAddress() == null ? "" : p.getAddress().getHostAddress();
                            if (from.equals(localIp)) {
                                continue;
                            }
                            if (!locations.containsKey(location)) {
                                locations.put(location, from);
                                lastSeen++;
                            }
                        } catch (SocketTimeoutException e) {
                            break;
                        } catch (Exception ignored) {
                        }
                    }

                    // 并行拉取设备描述，找 AVTransport 控制地址
                    if (!locations.isEmpty()) {
                        ExecutorService pool = Executors.newFixedThreadPool(
                                Math.min(6, locations.size()));
                        List<Callable<Device>> tasks = new ArrayList<>();
                        for (final Map.Entry<String, String> e : locations.entrySet()) {
                            final String loc = e.getKey();
                            final String from = e.getValue();
                            tasks.add(new Callable<Device>() {
                                @Override
                                public Device call() {
                                    return describe(loc, from);
                                }
                            });
                        }
                        try {
                            List<Future<Device>> futures = pool.invokeAll(tasks, 5, TimeUnit.SECONDS);
                            Set<String> controls = new HashSet<>();
                            for (Future<Device> f : futures) {
                                try {
                                    Device d = f.get();
                                    if (d != null && controls.add(d.controlUrl)) {
                                        result.add(d);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        } finally {
                            pool.shutdownNow();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (socket != null) {
                        socket.close();
                    }
                    if (lock != null && lock.isHeld()) {
                        lock.release();
                    }
                }
                cb.done(result);
            }
        }, "dlna-discover").start();
    }

    private static String msearch(String st) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + st + "\r\n"
                + "USER-AGENT: " + UA_DLNA + "\r\n"
                + "\r\n";
    }

    private static String header(String response, String key) {
        for (String line : response.split("\r?\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase(key)) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    /** 拉取设备描述，找到 AVTransport 的控制地址 */
    private static Device describe(String location, String sourceIp) {
        Device d = describeOnce(location);
        if (d == null && sourceIp != null && !sourceIp.isEmpty()) {
            // 少数电视给出的 LOCATION 主机名在局域网解析不了，用响应来源 IP 再试一次
            try {
                URL u = new URL(location);
                if (!sourceIp.equals(u.getHost())) {
                    URL fixed = new URL(u.getProtocol(), sourceIp, u.getPort(), u.getFile());
                    d = describeOnce(fixed.toString());
                }
            } catch (Exception ignored) {
            }
        }
        return d;
    }

    private static Device describeOnce(String location) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(location);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("User-Agent", UA_DLNA);
            conn.setRequestProperty("Connection", "close");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                bos.write(b, 0, n);
                if (bos.size() > 512 * 1024) {
                    break;
                }
            }
            in.close();
            String xml = new String(bos.toByteArray(), StandardCharsets.UTF_8);

            String friendly = null;
            String urlBase = null;
            String serviceType = null;
            String controlRel = null;
            String foundControl = null;
            String foundType = null;
            boolean inService = false;

            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "UTF-8");
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("service".equalsIgnoreCase(tag)) {
                        inService = true;
                        serviceType = null;
                        controlRel = null;
                    } else if (!inService && "friendlyName".equalsIgnoreCase(tag) && friendly == null) {
                        friendly = safeText(parser);
                    } else if (!inService && "URLBase".equalsIgnoreCase(tag) && urlBase == null) {
                        urlBase = safeText(parser);
                    } else if (inService && "serviceType".equalsIgnoreCase(tag)) {
                        serviceType = safeText(parser);
                    } else if (inService && "controlURL".equalsIgnoreCase(tag)) {
                        controlRel = safeText(parser);
                    }
                } else if (event == XmlPullParser.END_TAG && "service".equalsIgnoreCase(tag)) {
                    if (serviceType != null && serviceType.contains("AVTransport")
                            && controlRel != null && !controlRel.trim().isEmpty()) {
                        // 同时存在 v1/v2 时优先高版本
                        if (foundControl == null || serviceType.compareTo(foundType) > 0) {
                            foundControl = controlRel.trim();
                            foundType = serviceType.trim();
                        }
                    }
                    inService = false;
                }
                event = parser.next();
            }
            if (foundControl == null) {
                return null;
            }
            URL base = url;
            if (urlBase != null && !urlBase.trim().isEmpty()) {
                try {
                    base = new URL(urlBase.trim());
                } catch (Exception ignored) {
                }
            }
            Device d = new Device();
            d.name = friendly == null || friendly.trim().isEmpty() ? url.getHost() : friendly.trim();
            d.location = location;
            d.controlUrl = new URL(base, foundControl).toString();
            return d;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String safeText(XmlPullParser parser) {
        try {
            String t = parser.nextText();
            return t == null ? "" : t.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* 投屏控制                                                            */
    /* ------------------------------------------------------------------ */

    public static void play(final Device device, final String rawUrl, final String title,
                            final ActionCb cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Context ctx = App.ctx();
                if (rawUrl == null || rawUrl.trim().isEmpty()) {
                    cb.done(false, "当前没有可投屏的播放地址");
                    return;
                }
                String lower = rawUrl.toLowerCase(Locale.US);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    cb.done(false, "该地址不是 HTTP 流，电视无法直接播放");
                    return;
                }

                // 1. 探测真实地址：跟随跳转、判断 HLS、确认电视是否拉得动
                Probe probe = probe(rawUrl);
                String target = probe == null ? rawUrl : probe.finalUrl;
                String referer = probe == null ? null : probe.referer;
                boolean proxied = false;
                if (probe != null && probe.needsProxy && ctx != null) {
                    String local = CastProxy.get(ctx).proxyUrl(target, referer);
                    if (local != null) {
                        target = local;
                        proxied = true;
                    }
                }
                boolean hls = probe != null ? probe.hls
                        : (isHlsUrl(rawUrl) || !hasKnownVideoExt(rawUrl));
                String mime = probe != null && probe.mime != null
                        ? probe.mime : (hls ? "application/vnd.apple.mpegurl" : "video/mp4");

                // 2. 按格式下发地址，遇到 714/715 等类型错误时降级重试
                List<String> mimes = new ArrayList<>();
                if (hls) {
                    mimes.add("application/vnd.apple.mpegurl");
                    mimes.add("application/x-mpegURL");
                } else if (mime != null && !"application/vnd.apple.mpegurl".equals(mime)
                        && !"application/x-mpegURL".equals(mime)) {
                    mimes.add(mime);
                }
                if (!mimes.contains("video/mp4")) {
                    mimes.add("video/mp4");
                }
                if (mimes.isEmpty()) {
                    mimes.add("video/mp4");
                }

                SoapResult last = null;
                for (String m : mimes) {
                    last = setUri(device.controlUrl, target, didl(title, target, m));
                    if (last.ok) {
                        break;
                    }
                    if (last.upnp != 714 && last.upnp != 715 && last.upnp != 710
                            && last.upnp != 701 && last.upnp != 718) {
                        break;
                    }
                }
                if (last == null || !last.ok) {
                    if (last != null && last.error != null) {
                        cb.done(false, explain(last, proxied));
                        return;
                    }
                    // 最后尝试不带元数据，部分电视只认 CurrentURI
                    SoapResult bare = setUri(device.controlUrl, target, "");
                    if (!bare.ok) {
                        cb.done(false, explain(bare, proxied));
                        return;
                    }
                }

                SoapResult pr = soap(device.controlUrl, "Play",
                        "<InstanceID>0</InstanceID><Speed>1</Speed>");
                if (!pr.ok && pr.upnp == 701) {
                    // 部分电视刚接收到地址时还在切换，稍等再试一次
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {
                    }
                    pr = soap(device.controlUrl, "Play",
                            "<InstanceID>0</InstanceID><Speed>1</Speed>");
                }
                if (!pr.ok) {
                    cb.done(false, explain(pr, proxied));
                    return;
                }

                // 3. 确认电视确实进入了播放状态
                String state = waitPlaying(device.controlUrl);
                if ("STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state)) {
                    cb.done(false, "电视已收到地址但没有播放（" + state
                            + "），可能是电视无法访问该视频地址或格式不支持");
                    return;
                }

                current = device;
                currentUrl = target;
                cb.done(true, "已投屏到 " + device.name);
            }
        }, "dlna-cast").start();
    }

    public static void stop(final ActionCb cb) {
        final Device device = current;
        if (device != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = soap(device.controlUrl, "Stop",
                            "<InstanceID>0</InstanceID>").ok;
                    current = null;
                    currentUrl = "";
                    Context ctx = App.ctx();
                    if (ctx != null) {
                        CastProxy.get(ctx).release();
                    }
                    if (cb != null) {
                        cb.done(ok, ok ? "已停止投屏" : "已断开投屏");
                    }
                }
            }, "dlna-stop").start();
            return;
        }
        currentUrl = "";
        if (cb != null) {
            cb.done(true, "当前没有投屏");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 地址探测                                                            */
    /* ------------------------------------------------------------------ */

    private static final class Probe {
        String finalUrl;
        String mime;
        String referer;
        boolean hls;
        boolean needsProxy;
    }

    /** 探测最终地址与内容类型；失败返回 null（仍按原始地址尝试） */
    private static Probe probe(String url) {
        String current = url;
        for (int hop = 0; hop <= 5; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("User-Agent", UA_DLNA);
                conn.setRequestProperty("Accept", "*/*");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) {
                        return null;
                    }
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                String ct = conn.getContentType();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                boolean extPlaylist = false;
                if (in != null) {
                    byte[] head = new byte[1024];
                    int n = 0;
                    try {
                        while (n < head.length) {
                            int r = in.read(head, n, head.length - n);
                            if (r <= 0) {
                                break;
                            }
                            n += r;
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                    if (n > 0) {
                        extPlaylist = new String(head, 0, n, StandardCharsets.UTF_8)
                                .contains("#EXTM3U");
                    }
                }
                Probe p = new Probe();
                p.finalUrl = current;
                boolean ctHls = ct != null && ct.toLowerCase(Locale.US).contains("mpegurl");
                p.hls = ctHls || extPlaylist || isHlsUrl(current);
                p.mime = p.hls ? "application/vnd.apple.mpegurl" : mimeOf(ct, current);
                p.needsProxy = current.toLowerCase(Locale.US).startsWith("https://")
                        || code == 401 || code == 403 || code >= 500;
                if (code == 401 || code == 403) {
                    try {
                        URL u = new URL(current);
                        p.referer = u.getProtocol() + "://" + u.getHost() + "/";
                    } catch (Exception ignored) {
                    }
                }
                conn.disconnect();
                return p;
            } catch (Exception e) {
                if (conn != null) {
                    conn.disconnect();
                }
                return null;
            }
        }
        return null;
    }

    private static boolean isHlsUrl(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        return u.endsWith(".m3u8") || u.endsWith(".m3u");
    }

    private static boolean hasKnownVideoExt(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        return u.endsWith(".mp4") || u.endsWith(".m4v") || u.endsWith(".flv")
                || u.endsWith(".mkv") || u.endsWith(".ts");
    }

    private static String mimeOf(String contentType, String url) {
        if (contentType != null && !contentType.isEmpty()) {
            String ct = contentType.toLowerCase(Locale.US);
            int semi = ct.indexOf(';');
            if (semi > 0) {
                ct = ct.substring(0, semi).trim();
            }
            if (ct.startsWith("video/") || ct.startsWith("audio/")
                    || ct.contains("mpegurl") || ct.contains("mp2t")) {
                return ct;
            }
        }
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        if (u.endsWith(".mp4") || u.endsWith(".m4v")) {
            return "video/mp4";
        }
        if (u.endsWith(".flv")) {
            return "video/x-flv";
        }
        if (u.endsWith(".ts")) {
            return "video/mp2t";
        }
        if (u.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        return "video/mp4";
    }

    /* ------------------------------------------------------------------ */
    /* SOAP / DIDL                                                         */
    /* ------------------------------------------------------------------ */

    private static final class SoapResult {
        boolean ok;
        int http;
        int upnp;
        String error;
    }

    private static SoapResult setUri(String controlUrl, String url, String meta) {
        return soap(controlUrl, "SetAVTransportURI",
                "<InstanceID>0</InstanceID>"
                        + "<CurrentURI>" + xml(url) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + xml(meta) + "</CurrentURIMetaData>");
    }

    private static String didl(String title, String url, String mime) {
        String t = title == null || title.trim().isEmpty() ? "视频" : title.trim();
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + xml(t) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + xml(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    private static SoapResult soap(String controlUrl, String action, String args) {
        SoapResult r = new SoapResult();
        HttpURLConnection conn = null;
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:" + action + " xmlns:u=\"" + AVT + "\">"
                    + args
                    + "</u:" + action + "></s:Body></s:Envelope>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(controlUrl).openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + AVT + "#" + action + "\"");
            conn.setRequestProperty("Connection", "close");
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            r.http = code;
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            if (code >= 200 && code < 300) {
                r.ok = true;
                return r;
            }
            r.upnp = intTag(text, "errorCode");
            return r;
        } catch (Exception e) {
            r.error = e instanceof SocketTimeoutException ? "连接超时" : "无法连接电视";
            return r;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 读 GetTransportInfo，轮询到 PLAYING/TRANSITIONING；超时返回最后的已知状态 */
    private static String waitPlaying(String controlUrl) {
        String last = null;
        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(i == 0 ? 1500 : 2000);
            } catch (InterruptedException ignored) {
            }
            String state = transportState(controlUrl);
            if (state != null) {
                last = state;
                if ("PLAYING".equals(state) || "TRANSITIONING".equals(state)
                        || "PAUSED_PLAYBACK".equals(state)) {
                    return state;
                }
            }
        }
        return last == null ? "UNKNOWN" : last;
    }

    private static String transportState(String controlUrl) {
        HttpURLConnection conn = null;
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:GetTransportInfo xmlns:u=\"" + AVT + "\">"
                    + "<InstanceID>0</InstanceID>"
                    + "</u:GetTransportInfo></s:Body></s:Envelope>";
            conn = (HttpURLConnection) new URL(controlUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + AVT + "#GetTransportInfo\"");
            conn.setRequestProperty("Connection", "close");
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
                return null;
            }
            String text = readAll(conn.getInputStream());
            int s = text.indexOf("<CurrentTransportState>");
            if (s < 0) {
                return null;
            }
            s += "<CurrentTransportState>".length();
            int e = text.indexOf("</CurrentTransportState>", s);
            if (e < 0) {
                return null;
            }
            return text.substring(s, e).trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String explain(SoapResult r, boolean proxied) {
        String suffix = proxied ? "（已尝试本地转发）" : "";
        if (r == null) {
            return "投屏失败" + suffix;
        }
        if (r.error != null) {
            return r.error + "，请确认电视与手机在同一网络" + suffix;
        }
        if (r.upnp == 714 || r.upnp == 715 || r.upnp == 718) {
            return "电视不支持该视频格式（" + r.upnp + "），可换清晰度或换源后重试" + suffix;
        }
        if (r.upnp == 701) {
            return "电视当前无法播放（701），请先在电视上退出其他投屏" + suffix;
        }
        if (r.upnp == 716) {
            return "电视被占用（716），请先停止电视上其他播放" + suffix;
        }
        if (r.upnp > 0) {
            return "电视拒绝了投屏请求（错误码 " + r.upnp + "）" + suffix;
        }
        if (r.http > 0) {
            return "电视拒绝了投屏请求（HTTP " + r.http + "）" + suffix;
        }
        return "投屏失败" + suffix;
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[2048];
            int n;
            while ((n = in.read(b)) > 0) {
                bos.write(b, 0, n);
                if (bos.size() > 256 * 1024) {
                    break;
                }
            }
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static int intTag(String xml, String tag) {
        if (xml == null) {
            return 0;
        }
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) {
            return 0;
        }
        s += tag.length() + 2;
        int e = xml.indexOf("</" + tag + ">", s);
        if (e < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(xml.substring(s, e).trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String xml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
