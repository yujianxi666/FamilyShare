package com.family.share.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.family.share.config.AppConfig;
import com.family.share.data.Prefs;
import com.family.share.service.LocationReportService;
/**
 * 开机自启接收器：手机开机 / 应用升级后自动拉起后台位置上报服务。
 * （小米/华为/OPPO/vivo 等系统还需用户在“自启动管理”中允许本应用）
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Prefs prefs = Prefs.get(context);
        if (!prefs.shareEnabled() || prefs.familyId().isEmpty()) {
            return; // 用户尚未开启共享，不自动启动
        }
        Intent service = new Intent(context, LocationReportService.class)
                .setAction(AppConfig.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {
            // Android 12+ 后台启动前台服务可能被限制（ForegroundServiceStartNotAllowedException），
            // 交给 5 分钟看门狗或 30 分钟上报闹钟兜底，避免接收器崩溃导致闪退。
        }
        // 开机后（重新）注册系统级看门狗，保证被系统清理后仍能自动拉起
        KeepAliveReceiver.scheduleWatchdogJob(context);
    }
}
