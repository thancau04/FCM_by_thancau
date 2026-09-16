package io.github.muntashirakon.setedit;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import io.github.muntashirakon.setedit.adapters.AbsRecyclerAdapter;
import io.github.muntashirakon.setedit.adapters.AdapterProvider;
import io.github.muntashirakon.setedit.adapters.SettingsRecyclerAdapter;
import io.github.muntashirakon.setedit.boot.ActionItem;
import io.github.muntashirakon.setedit.boot.BootUtils;
import io.github.muntashirakon.setedit.shortcut.ShortcutUtils;
import io.github.muntashirakon.setedit.utils.ActionResult;
import io.github.muntashirakon.util.UiUtils;
import me.zhanghai.android.fastscroll.FastScrollerBuilder;

import com.thancau.setedit.R;

public class EditorActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener,
        SearchView.OnQueryTextListener {
    private static final String SELECTED_TABLE = "SELECTED_TABLE";

    @NonNull
    private final AdapterProvider adapterProvider = new AdapterProvider(this);

    @Nullable
    private AppCompatSpinner spinnerTable;
    @Nullable
    private SearchView searchView;
    private ExtendedFloatingActionButton addNewItem;
    private AbsRecyclerAdapter adapter;
    private RecyclerView listView;
    private SharedPreferences preferences;
    private Timer refreshTimer;

    // Launcher lưu file JSON
    private final ActivityResultLauncher<String> post21SaveLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri == null) return;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IOException();
                    saveAsJson(os);
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                } catch (Throwable th) {
                    th.printStackTrace();
                    Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
                }
            });

    // Launcher xin quyền POST_NOTIFICATIONS
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Không cần xử lý thêm — service vẫn chạy dù quyền bị từ chối
            });

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void displayOneTimeWarningDialog() {
        final SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        boolean hasWarned = preferences.getBoolean("has_warned", false);
        if (hasWarned) return;
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.startup_warning)
                .setNegativeButton(R.string.close, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) {
                neg.setTextColor(ContextCompat.getColor(this, R.color.fb_text_secondary));
            }
        });
        dialog.show();
        preferences.edit().putBoolean("has_warned", true).apply();
    }

    /**
     * Kiểm tra & yêu cầu quyền POST_NOTIFICATIONS (Android 13+).
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    /**
     * Kiểm tra quyền MANAGE_EXTERNAL_STORAGE (All Files Access).
     * Nếu chưa cấp, mở Settings để người dùng cấp thủ công.
     */
    private void checkAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    // Fallback nếu thiết bị không hỗ trợ intent trực tiếp
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(fallback);
                }
            }
        }
    }

    /**
     * Khởi động AutoApplyService.
     */
    private void startAutoApplyService() {
        Intent serviceIntent = new Intent(this, AutoApplyService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    /**
     * Gửi lệnh áp dụng ngay tới AutoApplyService.
     */
    private void triggerApplyNow() {
        Intent intent = new Intent(this, AutoApplyService.class);
        intent.setAction(AutoApplyService.ACTION_APPLY_NOW);
        ContextCompat.startForegroundService(this, intent);
        Toast.makeText(this, "AFCM: Đang áp dụng cấu hình từ TC.json…", Toast.LENGTH_SHORT).show();
    }

    /**
     * Gửi lệnh manual sync tới AutoApplyService (nút SYNC NOW trên header).
     */
    private void triggerManualSync() {
        Intent intent = new Intent(this, AutoApplyService.class);
        intent.setAction(AutoApplyService.ACTION_APPLY_NOW);
        ContextCompat.startForegroundService(this, intent);
        Toast.makeText(this, "Syncing FCM settings...", Toast.LENGTH_SHORT).show();
    }

    // -----------------------------------------------------------------------
    // Dialog thêm item mới
    // -----------------------------------------------------------------------

    public void addNewItemDialog() {
        View editorDialogView = getLayoutInflater().inflate(R.layout.dialog_new, null);
        EditText keyNameView = editorDialogView.findViewById(R.id.txtName);
        EditText keyValueView = editorDialogView.findViewById(R.id.txtValue);
        MaterialCheckBox performOnReboot = editorDialogView.findViewById(R.id.checkbox);
        MaterialCheckBox performViaShortcut = editorDialogView.findViewById(R.id.checkbox_2);
        if (adapter.canSetOnReboot()) {
            performOnReboot.setVisibility(View.VISIBLE);
        } else performOnReboot.setVisibility(View.GONE);
        if (adapter.canCreateShortcut()) {
            performViaShortcut.setVisibility(View.VISIBLE);
        } else performViaShortcut.setVisibility(View.GONE);
        keyNameView.requestFocus();
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(editorDialogView)
                .setTitle(R.string.new_item)
                .setPositiveButton(R.string.save, ((d, which) -> {
                    Editable keyName = keyNameView.getText();
                    Editable keyValue = keyValueView.getText();
                    if (TextUtils.isEmpty(keyName) || keyValue == null) return;
                    adapter.create(keyName.toString(), keyValue.toString());
                    if (adapter.canSetOnReboot() && performOnReboot.isChecked()) {
                        ActionItem actionItem = new ActionItem(ActionResult.TYPE_CREATE, EditorUtils.toTableType(adapter.getListType()), keyName.toString(), keyValue.toString());
                        BootUtils.add(this, actionItem);
                    }
                    if (adapter.canCreateShortcut() && performViaShortcut.isChecked()) {
                        ActionItem actionItem = new ActionItem(ActionResult.TYPE_CREATE, EditorUtils.toTableType(adapter.getListType()), keyName.toString(), keyValue.toString());
                        ShortcutUtils.displayShortcutTypeChooserDialog(this, actionItem);
                    }
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setTextColor(ContextCompat.getColor(this, R.color.fb_blue));
                pos.setTypeface(null, Typeface.BOLD);
            }
            Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) {
                neg.setTextColor(ContextCompat.getColor(this, R.color.fb_text_secondary));
            }
        });
        dialog.show();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onCreate(Bundle bundle) {
        preferences = getSharedPreferences("prefs", MODE_PRIVATE);
        int mode = preferences.getInt("theme", AppCompatDelegate.getDefaultNightMode());
        AppCompatDelegate.setDefaultNightMode(mode);
        super.onCreate(bundle);
        setTitle("FCM - TC");
        setTaskDescription(new ActivityManager.TaskDescription("FCM - TC"));
        setContentView(R.layout.activity_editor);
        setSupportActionBar(findViewById(R.id.toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.toolbar_custom_view);
            View actionBarView = actionBar.getCustomView();
            // Item view
            spinnerTable = actionBarView.findViewById(R.id.spinner);
            spinnerTable.setOnItemSelectedListener(this);
            ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(this, R.array.settings_table, R.layout.item_spinner);
            spinnerAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
            spinnerTable.setAdapter(spinnerAdapter);
        }
        // List view
        listView = findViewById(R.id.recycler_view);
        listView.setLayoutManager(new LinearLayoutManager(this));
        new FastScrollerBuilder(listView).useMd2Style().build();
        // Add efab
        addNewItem = findViewById(R.id.efab);
        addNewItem.setOnClickListener(v -> {
            if (adapter instanceof SettingsRecyclerAdapter) {
                Boolean isGranted = EditorUtils.checkSettingsPermission(this, ((SettingsRecyclerAdapter) adapter).getSettingsType());
                if (isGranted == null) return;
                if (isGranted) {
                    addNewItemDialog();
                } else {
                    EditorUtils.displayGrantPermissionMessage(this);
                }
            }
        });
        UiUtils.applyWindowInsetsAsMargin(addNewItem);

        // SYNC NOW button handler
        TextView btnSyncNow = findViewById(R.id.btn_sync_now);
        if (btnSyncNow != null) {
            btnSyncNow.setOnClickListener(v -> triggerManualSync());
        }

        // Display warning if it's the first time
        displayOneTimeWarningDialog();

        // === AFCM: Quyền và khởi động service ===
        requestNotificationPermissionIfNeeded();
        checkAllFilesAccessPermission();
        startAutoApplyService();

        // Refresh settings after 5 seconds
        refreshTimer = new Timer();
        refreshTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> {
                    if (adapter != null) adapter.refresh();
                });
            }
        }, 5000, 5000);
    }

    @Override
    protected void onDestroy() {
        // Hủy Timer tránh leak Activity reference
        if (refreshTimer != null) {
            refreshTimer.cancel();
            refreshTimer = null;
        }
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Menu
    // -----------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_editor_actions, menu);
        // Search view
        searchView = (SearchView) menu.findItem(R.id.action_search).getActionView();
        if (searchView != null) {
            searchView.setOnQueryTextListener(this);
            searchView.setQueryHint("Search settings...");
            searchView.setBackgroundResource(R.drawable.bg_search_view);
            // Search text
            EditText searchEditText = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
            if (searchEditText != null) {
                searchEditText.setTextColor(ContextCompat.getColor(this, R.color.fb_text_primary));
                searchEditText.setHintTextColor(ContextCompat.getColor(this, R.color.fb_text_hint));
                searchEditText.setTextSize(14f);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    searchEditText.setTextCursorDrawable(R.drawable.cursor_blue);
                } else {
                    try {
                        java.lang.reflect.Field f = TextView.class.getDeclaredField("mCursorDrawableRes");
                        f.setAccessible(true);
                        f.set(searchEditText, R.drawable.cursor_blue);
                    } catch (Exception ignored) {}
                }
            }
            // Search icon
            ImageView searchIcon = searchView.findViewById(androidx.appcompat.R.id.search_button);
            if (searchIcon != null) {
                searchIcon.setColorFilter(ContextCompat.getColor(this, R.color.fb_text_secondary));
            }
            ImageView searchMagIcon = searchView.findViewById(androidx.appcompat.R.id.search_mag_icon);
            if (searchMagIcon != null) {
                searchMagIcon.setColorFilter(ContextCompat.getColor(this, R.color.fb_text_secondary));
            }
            // Clear / Close icon
            ImageView closeIcon = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
            if (closeIcon != null) {
                closeIcon.setColorFilter(ContextCompat.getColor(this, R.color.fb_text_secondary));
            }
            // Remove default underline in search plate
            View searchPlate = searchView.findViewById(androidx.appcompat.R.id.search_plate);
            if (searchPlate != null) {
                searchPlate.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export) {
            post21SaveLauncher.launch(getFileName());
            return true;
        } else if (id == R.id.action_apply_whitelist) {
            triggerApplyNow();
            return true;
        } else if (id == R.id.action_theme) {
            List<Integer> themeMap = new ArrayList<>(4);
            // Sequence must be preserved
            themeMap.add(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            themeMap.add(AppCompatDelegate.MODE_NIGHT_NO);
            themeMap.add(AppCompatDelegate.MODE_NIGHT_YES);
            themeMap.add(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
            int mode = preferences.getInt("theme", AppCompatDelegate.getDefaultNightMode());
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.theme)
                    .setSingleChoiceItems(R.array.theme_options, themeMap.indexOf(mode), (dialog, which) -> {
                        int newMode = themeMap.get(which);
                        preferences.edit().putInt("theme", newMode).apply();
                        AppCompatDelegate.setDefaultNightMode(newMode);
                        dialog.dismiss();
                    })
                    .show();
        }
        return super.onOptionsItemSelected(item);
    }

    // -----------------------------------------------------------------------
    // Spinner / Search callbacks
    // -----------------------------------------------------------------------

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id) {
        listView.setAdapter(adapter = adapterProvider.getRecyclerAdapter(position));
        if (adapter.canCreate()) {
            addNewItem.show();
        } else addNewItem.hide();
        if (searchView != null) {
            searchView.setQuery(null, false);
            searchView.clearFocus();
            searchView.setIconified(true);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        addNewItem.show();
    }

    @Override
    public void onRestoreInstanceState(@NonNull Bundle bundle) {
        if (spinnerTable != null) {
            spinnerTable.setSelection(bundle.getInt(SELECTED_TABLE));
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle bundle) {
        super.onSaveInstanceState(bundle);
        if (spinnerTable != null) {
            bundle.putInt(SELECTED_TABLE, spinnerTable.getSelectedItemPosition());
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (adapter != null) {
            adapter.filter(newText.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String getFileName() {
        return "SetEdit-" + System.currentTimeMillis() + ".json";
    }

    private void saveAsJson(OutputStream os) throws JSONException, IOException {
        String jsonString = EditorUtils.getJson(adapter.getAllItems(), adapter instanceof SettingsRecyclerAdapter ?
                ((SettingsRecyclerAdapter) adapter).getSettingsType() : null);
        os.write(jsonString.getBytes());
    }
}
