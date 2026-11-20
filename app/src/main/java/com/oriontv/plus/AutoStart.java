package com.oriontv.plus;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 开机自启动（不依赖 Root，不会弹授权框）：
 * 1. 开机广播里直接拉起界面；
 * 2. 同时设一个闹钟兜底（闹钟触发的启动系统默认放行）；
 * 3. 非 Root 设备需要「显示在其他应用上层」权限，系统才允许后台拉起界面。
 */
public class AutoStart {

    private static final int FALLBACK_REQUEST = 1001;
    private static final int JOB_ID = 2001;

    /**
     * 排一个持久化 JobScheduler 任务：重启后由系统自动恢复，
     * 不依赖开机广播（部分 Android 14 系统会拦截第三方开机广播）。
     */
    public static void scheduleBootJob(Context ctx) {
        scheduleBootJob(ctx, 6000);
    }

    /** 排一个持久化任务，reboot 后由系统恢复执行；latency 为首次执行延迟 */
    public static void scheduleBootJob(Context ctx, long latencyMs) {
        try {
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            android.app.job.JobInfo job = new android.app.job.JobInfo.Builder(
                    JOB_ID, new android.content.ComponentName(ctx, BootJobService.class))
                    .setPersisted(true)
                    .setMinimumLatency(latencyMs)
                    .setOverrideDeadline(latencyMs + 30000)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {
        }
    }

    /** App 是否已经在前台（避免重复拉起、反复弹 Root 授权框） */
    public static boolean isForeground(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list =
                    am.getRunningAppProcesses();
            if (list == null) {
                return false;
            }
            for (android.app.ActivityManager.RunningAppProcessInfo p : list) {
                if (ctx.getPackageName().equals(p.processName)) {
                    return p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 开机广播 / 持久化任务触发后拉起 App */
    public static void launch(final Context ctx, final String reason) {
        final boolean live = new Prefs(ctx).homeLive();
        // Android 14 只给「有悬浮窗权限」的应用放行后台启动，有权限时优先走这条，
        // 避免已 root 但没授权 su 的设备每次开机都弹 Root 授权框
        if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(ctx)) {
            startDirect(ctx, live);
            scheduleFallback(ctx, live);
            return;
        }
        if (startByRoot(ctx, live)) {
            return;
        }
        startDirect(ctx, live);
        scheduleFallback(ctx, live);
    }

    /**
     * Root 设备直接交给 shell 启动：Android 14 对后台启动 Activity 限制很严，
     * 但从 shell(uid 0) 执行 am start 不受限制，也不会有悬浮窗/自启动授权弹窗。
     */
    private static boolean startByRoot(Context ctx, boolean live) {
        try {
            String cmd = "am start -n " + ctx.getPackageName() + "/com.oriontv.plus."
                    + (live ? "LiveActivity" : "MainActivity");
            final Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final int[] code = {-1};
            Thread waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        code[0] = p.waitFor();
                    } catch (Throwable ignored) {
                    }
                }
            });
            waiter.start();
            waiter.join(6000);
            if (waiter.isAlive()) {
                p.destroy();
                return false;
            }
            return code[0] == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 设置页打开「开机自动启动」时的引导 */
    public static void enable(final Activity a) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(a)) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("再开一个权限就能自启")
                    .setMessage("为了让 App 在开机后自动打开，需要授予「显示在其他应用上层」权限。\n\n"
                            + "Android 14 起系统会拦截后台自启，没有这个权限重启后不会自动打开；"
                            + "已 Root 的设备在弹出 Root 授权时点「允许」也可以。\n\n"
                            + "如果手机/电视系统里还有「自启动管理」，请一并允许本应用。")
                    .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                a.startActivity(new Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + a.getPackageName())));
                            } catch (Exception e) {
                                Ui.toast(a, "请到系统设置里手动授予悬浮窗权限");
                            }
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } else {
            Ui.toast(a, "已开启：重启后会自动打开");
        }
    }

    private static Intent launchIntent(Context ctx, boolean live) {
        Intent i = new Intent(ctx, live ? LiveActivity.class : MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    private static void startDirect(Context ctx, boolean live) {
        try {
            ctx.startActivity(launchIntent(ctx, live));
        } catch (Throwable ignored) {
        }
    }

    /** 闹钟兜底：直接拉起被系统拦下时，过几秒由系统再发一次启动 */
    private static void scheduleFallback(Context ctx, boolean live) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, FALLBACK_REQUEST,
                    launchIntent(ctx, live), flags);
            PendingIntent show = PendingIntent.getActivity(ctx, FALLBACK_REQUEST + 1,
                    launchIntent(ctx, live), flags);
            try {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + 4000, show), pi);
            } catch (Throwable t) {
                // Android 14 默认不给精确闹钟权限，退化为普通闹钟
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pi);
            }
        } catch (Throwable ignored) {
        }
    }
}
