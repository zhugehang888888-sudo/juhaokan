package com.oriontv.plus;

import androidx.media3.exoplayer.ExoPlayer;

/**
 * 共享播放器持有者：用于小窗播放（DetailActivity）与全屏播放（PlayerActivity）
 * 之间无缝切换同一个 ExoPlayer 实例，避免重新缓冲。
 *
 * 生命周期由当前持有者（Activity）负责释放；切换时仅 detach，不 release。
 */
public final class PlayerHolder {

    private static ExoPlayer player;
    private static String url;

    private PlayerHolder() {
    }

    public static ExoPlayer get() {
        return player;
    }

    public static String getUrl() {
        return url;
    }

    /** 设置共享播放器；若已有不同实例，先释放旧的。 */
    public static void set(ExoPlayer p, String u) {
        if (player != null && player != p) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
        }
        player = p;
        url = u;
    }

    /** 仅更新当前播放地址（如切换集数/线路后），不释放播放器。 */
    public static void updateUrl(String u) {
        url = u;
    }

    /** 清空引用（由持有者在释放播放器后调用）。 */
    public static void clear() {
        player = null;
        url = null;
    }
}
