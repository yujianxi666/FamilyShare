package com.family.share.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import com.family.share.config.AppConfig;
import com.family.share.data.Prefs;
import com.family.share.service.LocationReportService;
import com.family.share.service.WatchdogJobService;

/**
 * 看门狗：每隔几分钟检查一次后台服务是否存活，被杀死则自动拉起。
 * - 由 AlarmManager（Doze 友好的 setAndAllowWhileIdle）周期性触发
 * - 自调度：每次触发后自动安排下一次，服务存活与否都持续运行
 * - 仅在用户已开启共享且已加入家庭时才重启服务
 * - Android 12+ 后台启动前台服务可能受限，异常时静默跳过，交给 30 分钟上报闹钟兜底
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!LocationReportService.isRunning) {
            Prefs prefs = Prefs.get(context);
            if (prefs.shareEnabled() && !prefs.familyId().isEmpty()) {
                try {
                    startService(context);
                } catch (Exception ignored) {
                    // 后台启动限制等，忽略，等下次触发
                }
            }
        }
        scheduleNext(context);
    }

    public static void startService(Context context) {
        Intent s = new Intent(context, LocationReportService.class).setAction(AppConfig.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(s);
        } else {
            context.startService(s);
        }
    }

    /** 安排下一次看门狗触发（服务 onCreate 与看门狗自身都会调用） */
    public static void scheduleNext(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(context, KeepAliveReceiver.class);
        PendingIntent pi = pendingIntent(context, i);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AppConfig.WATCHDOG_INTERVAL_MS, pi);
        // 同时注册系统级看门狗（JobScheduler）：双路兜底，重启后仍保留
        scheduleWatchdogJob(context);
    }

    /** JobScheduler 看门狗的 JobId */
    public static final int WATCHDOG_JOB_ID = 0x5701;

    /**
     * 注册系统级看门狗任务（15 分钟周期，重启后保留）。
     * 幂等：同一 JobId 重复 schedule 只会覆盖，不会叠加。
     */
    public static void scheduleWatchdogJob(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            ComponentName cn = new ComponentName(context, WatchdogJobService.class);
            JobInfo job = new JobInfo.Builder(WATCHDOG_JOB_ID, cn)
                    .setPersisted(true)                                   // 重启后保留（需 RECEIVE_BOOT_COMPLETED）
                    .setPeriodic(15 * 60 * 1000L)                         // JobScheduler 最小周期 15 分钟
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)    // 纯本地检查，不需要网络
                    .build();
            js.schedule(job);
        } catch (Exception ignored) {
            // 个别 ROM 限制 JobScheduler，忽略，靠闹钟兜底
        }
    }

    /** 取消看门狗（服务销毁时调用） */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(context, KeepAliveReceiver.class);
        am.cancel(pendingIntent(context, i));
    }

    private static PendingIntent pendingIntent(Context context, Intent intent) {
        return PendingIntent.getBroadcast(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
