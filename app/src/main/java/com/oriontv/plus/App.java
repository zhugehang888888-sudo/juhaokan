package com.oriontv.plus;

import android.app.Application;
import android.content.Context;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

public class App extends Application {

    private static App instance;

    private Prefs prefs;
    private Api api;
    private ImageLoader images;
    private SimpleCache mediaCache;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        api = new Api(this);
        images = new ImageLoader(this);
        try {
            mediaCache = new SimpleCache(
                    new File(getCacheDir(), "media"),
                    new LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L),
                    new StandaloneDatabaseProvider(this));
        } catch (Throwable ignored) {
            mediaCache = null;
        }
        if (prefs.bootAutoStart()) {
            // 保证重启后有一条持久化任务能把 App 拉起来
            AutoStart.scheduleBootJob(this);
        }
    }

    public static App get() {
        return instance;
    }

    public static Context ctx() {
        return instance;
    }

    public Prefs prefs() {
        return prefs;
    }

    public Api api() {
        return api;
    }

    public ImageLoader images() {
        return images;
    }

    public SimpleCache mediaCache() {
        return mediaCache;
    }

    public void clearMediaCache() {
        if (mediaCache != null) {
            mediaCache.release();
            mediaCache = null;
        }
        deleteDir(new File(getCacheDir(), "media"));
        try {
            mediaCache = new SimpleCache(
                    new File(getCacheDir(), "media"),
                    new LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L),
                    new StandaloneDatabaseProvider(this));
        } catch (Throwable ignored) {
            mediaCache = null;
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDir(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
