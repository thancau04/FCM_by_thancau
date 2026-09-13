package io.github.muntashirakon.setedit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * BootReceiver (root package) — Lắng nghe ACTION_BOOT_COMPLETED và khởi động
 * AutoApplyService với ACTION_BOOT_APPLY để kích hoạt cơ chế retry
 * (chờ storage mount trước khi đọc TC.json).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, AutoApplyService.class);
            serviceIntent.setAction(AutoApplyService.ACTION_BOOT_APPLY);
            // retry_count = 0: lần đầu, service sẽ chờ 4000ms trước khi đọc file
            serviceIntent.putExtra("retry_count", 0);
            ContextCompat.startForegroundService(context, serviceIntent);
        }
    }
}
