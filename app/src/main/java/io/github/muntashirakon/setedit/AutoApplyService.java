package io.github.muntashirakon.setedit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AutoApplyService — Foreground Service tự động nạp cấu hình hệ thống từ
 * /storage/emulated/0/AFCM/TC.json.
 *
 * Chiến lược Dual-Write (targetSdk 22):
 * - Ghi Settings.System : không bị runtime block (targetSdk ≤ 22)
 * - Ghi Settings.Global : quyền WRITE_SECURE_SETTINGS
 * - Dirty-check kép : chỉ ghi bảng nào thực sự cần cập nhật
 * - Sanitize : chuẩn hóa khoảng trắng quanh dấu phẩy
 *
 * Trigger:
 * - ACTION_APPLY_NOW : từ EditorActivity (menu "Apply Whitelist Now")
 * - ACTION_BOOT_APPLY : từ BootReceiver, kèm retry storage-mount
 * - ACTION_SCREEN_OFF : BroadcastReceiver nội bộ (debounce 2000ms)
 * - POWER_CONNECTED / POWER_DISCONNECTED : cắm/rút sạc
 * - FileObserver : CLOSE_WRITE/MODIFY trên TC.json (debounce 1500ms)
 * - Heartbeat : kiểm tra định kỳ mỗi 30 phút
 */
public class AutoApplyService extends Service {

    public static final String TAG = "AutoApplyService";
    public static final String ACTION_APPLY_NOW = "ACTION_APPLY_NOW";
    public static final String ACTION_BOOT_APPLY = "ACTION_BOOT_APPLY";

    private static final int MAX_BOOT_RETRIES = 3;
    private static final long BOOT_FIRST_DELAY_MS = 4000L;
    private static final long BOOT_RETRY_DELAY_MS = 2000L;

    private static final String CHANNEL_ID = "afcm_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String AFCM_DIR = "AFCM";
    private static final String JSON_FILENAME = "TC.json";

    private static final long SCREEN_DEBOUNCE_MS = 2000L;
    private static final long FILE_DEBOUNCE_MS = 1500L;

    /** Chu kỳ Heartbeat: 30 phút (1_800_000ms) */
    private static final long HEARTBEAT_INTERVAL_MS = 30 * 60 * 1000L;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private ExecutorService executor;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private Handler mainHandler;
    private FileObserver fileObserver;

    private final AtomicLong lastScreenExecuteTime = new AtomicLong(0L);
    private final AtomicLong lastFileExecuteTime = new AtomicLong(0L);

    // -----------------------------------------------------------------------
    // Heartbeat Runnable — kiểm tra định kỳ mỗi 30 phút
    // -----------------------------------------------------------------------

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Heartbeat 30m triggered.");
            executeApplySettings("heartbeat_30m");
            // Lập lịch lần tiếp theo
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    // -----------------------------------------------------------------------
    // BroadcastReceiver — SCREEN_OFF / POWER_CONNECTED / POWER_DISCONNECTED
    // -----------------------------------------------------------------------

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null)
                return;

            String action = intent.getAction();

            switch (action) {
                case Intent.ACTION_SCREEN_OFF: {
                    long now = System.currentTimeMillis();
                    if (now - lastScreenExecuteTime.get() < SCREEN_DEBOUNCE_MS) {
                        Log.d(TAG, "ScreenOff debounce: bỏ qua.");
                        return;
                    }
                    lastScreenExecuteTime.set(now);
                    executeApplySettings("screen_off");
                    break;
                }
                case Intent.ACTION_POWER_CONNECTED:
                    Log.d(TAG, "Power connected → kiểm tra cấu hình.");
                    executeApplySettings("power_connected");
                    break;
                case Intent.ACTION_POWER_DISCONNECTED:
                    Log.d(TAG, "Power disconnected → kiểm tra cấu hình.");
                    executeApplySettings("power_disconnected");
                    break;
                default:
                    break;
            }
        }
    };

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Tạo NotificationChannel (bắt buộc từ Android O để Foreground Service không bị
        // kill)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "thancau — Auto Config",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Tự động nạp cấu hình hệ thống từ TC.json");
            channel.enableVibration(false);
            channel.setSound(null, null);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FCM by thancau")
                .setContentText("Đang khởi động…")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        Notification notification = notificationBuilder.build();

        // Android 14+ yêu cầu khai báo foregroundServiceType khi gọi startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Đăng ký receiver: SCREEN_OFF + POWER_CONNECTED + POWER_DISCONNECTED
        IntentFilter eventFilter = new IntentFilter();
        eventFilter.addAction(Intent.ACTION_SCREEN_OFF);
        eventFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        eventFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        ContextCompat.registerReceiver(this, screenReceiver, eventFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Khởi động FileObserver theo dõi thư mục AFCM/
        startFileObserver();

        // Khởi động Heartbeat Timer — lần đầu chạy sau 30 phút
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        Log.i(TAG, "Heartbeat scheduled: mỗi " + (HEARTBEAT_INTERVAL_MS / 60000) + " phút.");

        updateNotification("Sẵn sàng theo dõi TC.json");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_STICKY;

        String action = intent.getAction();
        if (ACTION_APPLY_NOW.equals(action)) {
            executeApplySettings("manual");
        } else if (ACTION_BOOT_APPLY.equals(action)) {
            int retryCount = intent.getIntExtra("retry_count", 0);
            scheduleBootApply(retryCount);
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Không dừng service, đảm bảo tiến trình ngầm tiếp tục duy trì notification
    }

    @Override
    public void onDestroy() {
        // 1. Hủy Heartbeat Timer
        try {
            mainHandler.removeCallbacks(heartbeatRunnable);
            Log.d(TAG, "Heartbeat timer cancelled.");
        } catch (Exception e) {
            Log.w(TAG, "Heartbeat cancel error", e);
        }

        // 2. Ngừng FileObserver
        try {
            if (fileObserver != null) {
                fileObserver.stopWatching();
                fileObserver = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "FileObserver stopWatching error", e);
        }

        // 3. Hủy ScreenReceiver (bao gồm cả POWER events)
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "ScreenReceiver already unregistered", e);
        }

        // 4. Shutdown executor
        try {
            if (executor != null)
                executor.shutdownNow();
        } catch (Exception e) {
            Log.w(TAG, "Executor shutdown error", e);
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -----------------------------------------------------------------------
    // FileObserver — theo dõi AFCM/TC.json (BOOT_COMPLETED giữ nguyên)
    // -----------------------------------------------------------------------

    private void startFileObserver() {
        File afcmDir = new File(Environment.getExternalStorageDirectory(), AFCM_DIR);
        if (!afcmDir.exists()) {
            // noinspection ResultOfMethodCallIgnored
            afcmDir.mkdirs();
        }

        int mask = FileObserver.CLOSE_WRITE | FileObserver.MODIFY;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fileObserver = new FileObserver(afcmDir, mask) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    if (JSON_FILENAME.equals(path))
                        handleFileObserverEvent();
                }
            };
        } else {
            // noinspection deprecation
            fileObserver = new FileObserver(afcmDir.getAbsolutePath(), mask) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    if (JSON_FILENAME.equals(path))
                        handleFileObserverEvent();
                }
            };
        }

        fileObserver.startWatching();
        Log.d(TAG, "FileObserver theo dõi: " + afcmDir.getAbsolutePath());
    }

    private void handleFileObserverEvent() {
        long now = System.currentTimeMillis();
        if (now - lastFileExecuteTime.get() < FILE_DEBOUNCE_MS) {
            Log.d(TAG, "FileObserver debounce: bỏ qua.");
            return;
        }
        lastFileExecuteTime.set(now);
        Log.d(TAG, "TC.json thay đổi → nạp lại.");
        executeApplySettings("file_changed");
    }

    // -----------------------------------------------------------------------
    // Boot delay / retry (BOOT_COMPLETED)
    // -----------------------------------------------------------------------

    private void scheduleBootApply(final int retryCount) {
        long delay = (retryCount == 0) ? BOOT_FIRST_DELAY_MS : BOOT_RETRY_DELAY_MS;
        mainHandler.postDelayed(() -> {
            File storageRoot = Environment.getExternalStorageDirectory();
            if (storageRoot.exists() && storageRoot.canRead()) {
                Log.i(TAG, "Boot: storage sẵn sàng (retry=" + retryCount + ").");
                executeApplySettings("boot");
            } else if (retryCount < MAX_BOOT_RETRIES - 1) {
                Log.w(TAG, "Boot: chưa mount storage, retry " + (retryCount + 1));
                updateNotification("Chờ storage mount… (" + (retryCount + 1) + "/" + MAX_BOOT_RETRIES + ")");
                scheduleBootApply(retryCount + 1);
            } else {
                Log.e(TAG, "Boot: storage không sẵn sàng sau " + MAX_BOOT_RETRIES + " lần.");
                updateNotification("⚠ Không truy cập được storage");
            }
        }, delay);
    }

    // -----------------------------------------------------------------------
    // Core logic — Dual-Write với Dirty-Check kép + Safe Guard JSON
    // -----------------------------------------------------------------------

    private void executeApplySettings(String trigger) {
        executor.submit(() -> {
            Log.i(TAG, "executeApplySettings — trigger: " + trigger);
            updateNotification("Đang nạp cấu hình…");

            File jsonFile = new File(
                    Environment.getExternalStorageDirectory(), AFCM_DIR + "/" + JSON_FILENAME);

            // Safe Guard: file không tồn tại hoặc không đọc được
            if (!jsonFile.exists() || !jsonFile.canRead()) {
                Log.w(TAG, "Cảnh báo: File TC.json không hợp lệ hoặc bị xóa, giữ nguyên giá trị hệ thống hiện tại.");
                updateNotification("thancau: Cảnh báo file TC.json lỗi cú pháp");
                return;
            }

            try {
                byte[] bytes = Files.readAllBytes(jsonFile.toPath());
                String content = new String(bytes, StandardCharsets.UTF_8).trim();

                // Safe Guard: file rỗng
                if (content.isEmpty()) {
                    Log.w(TAG, "Cảnh báo: File TC.json không hợp lệ hoặc bị xóa, giữ nguyên giá trị hệ thống hiện tại.");
                    updateNotification("thancau: Cảnh báo file TC.json lỗi cú pháp");
                    return;
                }

                JSONObject jsonObject = new JSONObject(content);

                int successCount = 0;
                int skippedCount = 0;
                int totalKeys = jsonObject.length();

                Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try {
                        String rawValue = jsonObject.getString(key);

                        // Sanitize: loại bỏ khoảng trắng thừa quanh dấu phẩy
                        String cleanVal = rawValue.replaceAll("\\s*,\\s*", ",");

                        // Dirty-check kép: đọc giá trị hiện tại từ cả hai bảng
                        String curSys = Settings.System.getString(getContentResolver(), key);
                        String curGlob = Settings.Global.getString(getContentResolver(), key);

                        boolean needWriteSys = !cleanVal.equals(curSys);
                        boolean needWriteGlob = !cleanVal.equals(curGlob);

                        if (!needWriteSys && !needWriteGlob) {
                            // Cả hai bảng đã khớp — bỏ qua để tránh kích hoạt ContentObserver
                            Log.d(TAG, "Dirty-check skip: " + key);
                            skippedCount++;
                            continue;
                        }

                        // Ghi Settings.System nếu cần (targetSdk 22 không bị runtime block)
                        if (needWriteSys) {
                            try {
                                Settings.System.putString(getContentResolver(), key, cleanVal);
                                Log.d(TAG, "System write OK: " + key + " = " + cleanVal);
                            } catch (Exception ignored) {
                                Log.w(TAG, "System write FAIL: " + key);
                            }
                        }

                        // Ghi Settings.Global nếu cần (WRITE_SECURE_SETTINGS)
                        if (needWriteGlob) {
                            try {
                                Settings.Global.putString(getContentResolver(), key, cleanVal);
                                Log.d(TAG, "Global write OK: " + key + " = " + cleanVal);
                            } catch (Exception ignored) {
                                Log.w(TAG, "Global write FAIL: " + key);
                            }
                        }

                        successCount++;

                    } catch (Exception e) {
                        Log.e(TAG, "Lỗi xử lý key: " + key, e);
                    }
                }

                // Cập nhật thông báo: "thancau: Đang bảo vệ N keys • HH:mm:ss"
                String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date());
                String statusText = "thancau: Đang bảo vệ " + totalKeys + " keys • " + timeStr;
                if (successCount > 0) {
                    statusText += " (cập nhật " + successCount + ")";
                }

                Log.i(TAG, statusText);
                updateNotification(statusText);
                showToast(statusText);

            } catch (JSONException e) {
                // Safe Guard: lỗi cú pháp JSON — KHÔNG crash, KHÔNG xóa/ghi đè keys
                Log.w(TAG, "Cảnh báo: File TC.json không hợp lệ hoặc bị xóa, giữ nguyên giá trị hệ thống hiện tại.", e);
                updateNotification("thancau: Cảnh báo file TC.json lỗi cú pháp");
            } catch (IOException e) {
                // Safe Guard: lỗi đọc file — KHÔNG crash, KHÔNG xóa/ghi đè keys
                Log.w(TAG, "Cảnh báo: File TC.json không hợp lệ hoặc bị xóa, giữ nguyên giá trị hệ thống hiện tại.", e);
                updateNotification("thancau: Cảnh báo file TC.json lỗi cú pháp");
            }
        });
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    /** Cập nhật Notification từ bất kỳ luồng nào (thread-safe). */
    private void updateNotification(String statusText) {
        mainHandler.post(() -> {
            if (notificationBuilder == null || notificationManager == null)
                return;
            notificationBuilder.setContentText(statusText);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        });
    }

    /** Hiển thị Toast an toàn từ luồng nền. */
    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(AutoApplyService.this, message, Toast.LENGTH_SHORT).show());
    }
}
