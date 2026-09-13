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

import org.json.JSONObject;

import java.io.File;
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
 *   - Ghi Settings.System  : không bị runtime block (targetSdk ≤ 22)
 *   - Ghi Settings.Global  : quyền WRITE_SECURE_SETTINGS
 *   - Dirty-check kép      : chỉ ghi bảng nào thực sự cần cập nhật
 *   - Sanitize             : chuẩn hóa khoảng trắng quanh dấu phẩy
 *
 * Trigger:
 *   - ACTION_APPLY_NOW    : từ EditorActivity (menu "Apply Whitelist Now")
 *   - ACTION_BOOT_APPLY   : từ BootReceiver, kèm retry storage-mount
 *   - ACTION_SCREEN_OFF   : BroadcastReceiver nội bộ (debounce 2000ms)
 *   - FileObserver        : CLOSE_WRITE/MODIFY trên TC.json (debounce 1500ms)
 */
public class AutoApplyService extends Service {

    public static final String TAG              = "AutoApplyService";
    public static final String ACTION_APPLY_NOW  = "ACTION_APPLY_NOW";
    public static final String ACTION_BOOT_APPLY = "ACTION_BOOT_APPLY";

    private static final int    MAX_BOOT_RETRIES    = 3;
    private static final long   BOOT_FIRST_DELAY_MS = 4000L;
    private static final long   BOOT_RETRY_DELAY_MS = 2000L;

    private static final String CHANNEL_ID     = "afcm_channel";
    private static final int    NOTIFICATION_ID = 1001;
    private static final String AFCM_DIR       = "AFCM";
    private static final String JSON_FILENAME  = "TC.json";

    private static final long SCREEN_DEBOUNCE_MS = 2000L;
    private static final long FILE_DEBOUNCE_MS   = 1500L;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private ExecutorService            executor;
    private NotificationManager        notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private Handler                    mainHandler;
    private FileObserver               fileObserver;

    private final AtomicLong lastScreenExecuteTime = new AtomicLong(0L);
    private final AtomicLong lastFileExecuteTime   = new AtomicLong(0L);

    // -----------------------------------------------------------------------
    // BroadcastReceiver — màn hình tắt (SCREEN_OFF)
    // -----------------------------------------------------------------------

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
            long now = System.currentTimeMillis();
            if (now - lastScreenExecuteTime.get() < SCREEN_DEBOUNCE_MS) {
                Log.d(TAG, "ScreenOff debounce: bỏ qua.");
                return;
            }
            lastScreenExecuteTime.set(now);
            executeApplySettings("screen_off");
        }
    };

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler         = new Handler(Looper.getMainLooper());
        executor            = Executors.newSingleThreadExecutor();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Tạo NotificationChannel (bắt buộc từ Android O để Foreground Service không bị kill)
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
                .setContentTitle("thancau — AFCM")
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

        // Đăng ký receiver lắng nghe tắt màn hình (Android 14+ cần flag NOT_EXPORTED)
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenReceiver, screenFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Khởi động FileObserver theo dõi thư mục AFCM/
        startFileObserver();

        updateNotification("Sẵn sàng theo dõi TC.json");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

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
    public void onDestroy() {
        // 1. Ngừng FileObserver
        try {
            if (fileObserver != null) {
                fileObserver.stopWatching();
                fileObserver = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "FileObserver stopWatching error", e);
        }

        // 2. Hủy ScreenReceiver
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "ScreenReceiver already unregistered", e);
        }

        // 3. Shutdown executor
        try {
            if (executor != null) executor.shutdownNow();
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
            //noinspection ResultOfMethodCallIgnored
            afcmDir.mkdirs();
        }

        int mask = FileObserver.CLOSE_WRITE | FileObserver.MODIFY;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fileObserver = new FileObserver(afcmDir, mask) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    if (JSON_FILENAME.equals(path)) handleFileObserverEvent();
                }
            };
        } else {
            //noinspection deprecation
            fileObserver = new FileObserver(afcmDir.getAbsolutePath(), mask) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    if (JSON_FILENAME.equals(path)) handleFileObserverEvent();
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
    // Core logic — Dual-Write với Dirty-Check kép
    // -----------------------------------------------------------------------

    private void executeApplySettings(String trigger) {
        executor.submit(() -> {
            Log.i(TAG, "executeApplySettings — trigger: " + trigger);
            updateNotification("Đang nạp cấu hình…");

            File jsonFile = new File(
                    Environment.getExternalStorageDirectory(), AFCM_DIR + "/" + JSON_FILENAME);

            if (!jsonFile.exists() || !jsonFile.canRead()) {
                String msg = "thancau AFCM: Không tìm thấy TC.json";
                Log.w(TAG, msg + " | path=" + jsonFile.getAbsolutePath());
                updateNotification("⚠ Không tìm thấy TC.json");
                showToast(msg);
                return;
            }

            try {
                byte[]     bytes      = Files.readAllBytes(jsonFile.toPath());
                String     content    = new String(bytes, StandardCharsets.UTF_8).trim();
                JSONObject jsonObject = new JSONObject(content);

                int successCount = 0;
                int skippedCount = 0;
                int totalKeys    = jsonObject.length();

                Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try {
                        String rawValue = jsonObject.getString(key);

                        // Sanitize: loại bỏ khoảng trắng thừa quanh dấu phẩy
                        String cleanVal = rawValue.replaceAll("\\s*,\\s*", ",");

                        // Dirty-check kép: đọc giá trị hiện tại từ cả hai bảng
                        String curSys  = Settings.System.getString(getContentResolver(), key);
                        String curGlob = Settings.Global.getString(getContentResolver(), key);

                        boolean needWriteSys  = !cleanVal.equals(curSys);
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

                // Cập nhật thông báo theo format yêu cầu
                String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date());
                String statusText = "thancau: Đã đồng bộ " + successCount + "/" + totalKeys
                        + " keys • " + timeStr;
                if (skippedCount > 0) {
                    statusText += " (bỏ qua " + skippedCount + ")";
                }

                Log.i(TAG, statusText);
                updateNotification(statusText);
                showToast(statusText);

            } catch (Exception e) {
                String errMsg = "thancau AFCM: Lỗi parse TC.json — " + e.getMessage();
                Log.e(TAG, errMsg, e);
                updateNotification("⚠ Lỗi parse JSON");
                showToast(errMsg);
            }
        });
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    /** Cập nhật Notification từ bất kỳ luồng nào (thread-safe). */
    private void updateNotification(String statusText) {
        mainHandler.post(() -> {
            if (notificationBuilder == null || notificationManager == null) return;
            notificationBuilder.setContentText(statusText);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        });
    }

    /** Hiển thị Toast an toàn từ luồng nền. */
    private void showToast(String message) {
        mainHandler.post(() ->
                Toast.makeText(AutoApplyService.this, message, Toast.LENGTH_SHORT).show());
    }
}
