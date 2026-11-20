package com.oriontv.plus.player;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 去广告数据源：与网页端实现保持一致 —— 读取 m3u8 时移除
 * #EXT-X-DISCONTINUITY / CUE / SCTE35 等广告间断标记，避免广告插入造成的
 * 跳变与卡顿；同时统计实时下载速率用于界面显示。
 */
public final class AdBlockDataSource implements DataSource {

    public static final AtomicLong BYTES = new AtomicLong(0);

    private final DataSource upstream;
    private final boolean enabled;

    private byte[] filtered;
    private int offset;
    private boolean filtering;
    private Uri currentUri;
    private java.util.Map<String, List<String>> currentHeaders = java.util.Collections.emptyMap();

    public AdBlockDataSource(DataSource upstream, boolean enabled) {
        this.upstream = upstream;
        this.enabled = enabled;
    }

    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        private final boolean enabled;

        public Factory(DataSource.Factory upstream, boolean enabled) {
            this.upstream = upstream;
            this.enabled = enabled;
        }

        @Override
        public DataSource createDataSource() {
            return new AdBlockDataSource(upstream.createDataSource(), enabled);
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        currentUri = dataSpec.uri;
        String uri = dataSpec.uri.toString().toLowerCase(Locale.US);
        boolean playlist = uri.contains(".m3u8") || uri.contains("m3u8?");
        if (!enabled || !playlist) {
            filtering = false;
            filtered = null;
            try {
                long len = upstream.open(dataSpec);
                currentHeaders = upstream.getResponseHeaders();
                return len;
            } catch (IOException e) {
                android.util.Log.w("AdBlockSource", "open failed " + dataSpec.uri + " : " + e);
                throw e;
            }
        }

        try {
            DataSpec spec = dataSpec.buildUpon().setPosition(0).build();
            upstream.open(spec);
            currentHeaders = upstream.getResponseHeaders();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = upstream.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) {
                bos.write(buf, 0, n);
            }
            upstream.close();

            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            filtered = filterPlaylist(text).getBytes(StandardCharsets.UTF_8);
            offset = 0;
            filtering = true;
            android.util.Log.d("AdBlockSource", "playlist " + dataSpec.uri + " -> " + filtered.length + " bytes");
            return filtered.length;
        } catch (IOException e) {
            android.util.Log.w("AdBlockSource", "playlist fetch failed " + dataSpec.uri + " : " + e);
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int start, int length) throws IOException {
        if (!filtering) {
            int read = upstream.read(buffer, start, length);
            if (read > 0) {
                BYTES.addAndGet(read);
            }
            return read;
        }
        if (offset >= filtered.length) {
            return C.RESULT_END_OF_INPUT;
        }
        int count = Math.min(length, filtered.length - offset);
        System.arraycopy(filtered, offset, buffer, start, count);
        offset += count;
        BYTES.addAndGet(count);
        return count;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return currentUri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return currentHeaders;
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }

    private static String filterPlaylist(String src) {
        if (src == null || src.isEmpty()) {
            return "";
        }
        String[] lines = src.split("\n", -1);
        StringBuilder sb = new StringBuilder(src.length());
        for (String line : lines) {
            String t = line.trim().toUpperCase(Locale.US);
            if (t.startsWith("#EXT-X-DISCONTINUITY")
                    || t.startsWith("#EXT-X-CUE-OUT")
                    || t.startsWith("#EXT-X-CUE-IN")
                    || t.startsWith("#EXT-X-SCTE35")
                    || t.startsWith("#EXT-OATCLS-SCTE35")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
