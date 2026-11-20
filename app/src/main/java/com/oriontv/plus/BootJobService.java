package com.oriontv.plus;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.SystemClock;

/**
 * 开机自启兜底：JobScheduler 的持久化任务在重启后由系统自动恢复执行，
 * 不依赖 BOOT_COMPLETED 广播（部分 Android 14 定制系统会拦截第三方开机广播）。
 */
public class BootJobService extends JobService {

    /** 开机后只在这段时间内尝试拉起，之后只保留任务给下次重启用 */
    private static final long BOOT_WINDOW_MS = 180_000;

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean fg = AutoStart.isForeground(BootJobService.this);
                boolean bootWindow = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS;
                if (!fg && bootWindow && new Prefs(BootJobService.this).bootAutoStart()) {
                    AutoStart.launch(BootJobService.this, "job");
                }
                // 开机窗口内没起来就每分钟重试；之后每小时醒一次，保留任务给下次重启
                AutoStart.scheduleBootJob(BootJobService.this,
                        (fg || !bootWindow) ? 3600_000 : 60_000);
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
