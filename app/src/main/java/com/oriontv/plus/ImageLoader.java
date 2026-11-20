package com.oriontv.plus;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 轻量海报加载器：内存 + 磁盘 + 豆瓣图片走服务端代理。
 */
public class ImageLoader {

    private final OkHttpClient http;
    private final LruCache<String, Bitmap> mem;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Prefs prefs;

    public ImageLoader(android.content.Context c) {
        prefs = App.get().prefs();
        dir = new File(c.getCacheDir(), "img");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        mem = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        http = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void load(String url, ImageView iv) {
        load(url, iv, 0);
    }

    public void load(final String rawUrl, final ImageView iv, final int maxWidth) {
        if (iv == null) {
            return;
        }
        if (rawUrl == null || rawUrl.isEmpty()) {
            iv.setTag(null);
            iv.setImageDrawable(null);
            return;
        }
        String url = normalize(rawUrl);
        iv.setTag(url);
        Bitmap cached = mem.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        final int width = maxWidth > 0 ? maxWidth : 420;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = loadSync(url, width);
                if (bmp == null) {
                    return;
                }
                mem.put(url, bmp);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = iv.getTag();
                        if (url.equals(tag)) {
                            iv.setImageBitmap(bmp);
                        }
                    }
                });
            }
        });
    }

    private String normalize(String url) {
        if (url.contains("doubanio.com")) {
            return prefs.server() + "/api/image-proxy?url=" + Api.enc(url);
        }
        if (url.startsWith("/")) {
            return prefs.server() + url;
        }
        return url;
    }

    private Bitmap loadSync(String url, int targetWidth) {
        File f = new File(dir, md5(url));
        if (f.exists() && f.length() > 0) {
            Bitmap b = decode(f, targetWidth);
            if (b != null) {
                return b;
            }
            f.delete();
        }
        try {
            Request.Builder rb = new Request.Builder().url(url)
                    .header("Cookie", prefs.cookie())
                    .header("User-Agent", "Mozilla/5.0 (Android) OrionTVPlus");
            Response r = http.newCall(rb.build()).execute();
            if (!r.isSuccessful() || r.body() == null) {
                return null;
            }
            byte[] data = r.body().bytes();
            if (data.length == 0) {
                return null;
            }
            Bitmap b = decode(data, targetWidth);
            if (b != null) {
                try {
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(data);
                    fos.close();
                } catch (IOException ignored) {
                }
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap decode(File f, int targetWidth) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            o.inSampleSize = sample(o.outWidth, targetWidth);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable e) {
            return null;
        }
    }

    private Bitmap decode(byte[] data, int targetWidth) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = sample(o.outWidth, targetWidth);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable e) {
            return null;
        }
    }

    private int sample(int w, int target) {
        int s = 1;
        if (w > 0) {
            while (w / s > target * 2) {
                s *= 2;
            }
        }
        return s;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
