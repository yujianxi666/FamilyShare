package com.family.share.service;

import android.app.job.JobParameters;
import android.app.job.JobService;

import com.family.share.data.Prefs;
import com.family.share.receiver.KeepAliveReceiver;

/**
 * 系统级看门狗（JobScheduler）：
 * - 由系统按周期（15 分钟，JobScheduler 允许的最小周期）唤醒，比自己 setAndAllowWhileIdle 的自调度闹钟
 *   在激进杀后台的国产 ROM 上更可靠（系统统一调度，不受应用自身被限制的影响）
 * - setPersisted(true)：设备重启后任务仍保留（依赖 RECEIVE_BOOT_COMPLETED 权限）
 * - 只做一件事：发现前台服务被杀就把它拉起来；不做网络请求、不做计算，尽量不影响耗电
 *
 * 说明：微信那种“永生”主要靠厂商系统级白名单，普通 App 无法复制；这里能做的就是
 * 前台服务 + 自调度闹钟 + JobScheduler + 开机自启 多路兜底，并引导用户加入省电白名单。
 */
public class WatchdogJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            if (!LocationReportService.isRunning) {
                Prefs prefs = Prefs.get(this);
                if (prefs.shareEnabled() && !prefs.familyId().isEmpty()) {
                    KeepAliveReceiver.startService(this);
                }
            }
        } catch (Exception ignored) {
            // 后台启动前台服务可能被系统限制，静默跳过，等下次周期
        }
        return false; // 同步完成，无需继续占用线程
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 被系统中断（如省电策略）则按要求稍后重试
    }
}
