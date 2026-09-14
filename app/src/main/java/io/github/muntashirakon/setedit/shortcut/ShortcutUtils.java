package io.github.muntashirakon.setedit.shortcut;

import android.content.Context;

import android.graphics.Typeface;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import com.thancau.setedit.R;
import io.github.muntashirakon.setedit.boot.ActionItem;

public final class ShortcutUtils {
    @NonNull
    public static List<ShortcutItem> getShortcutItems(@NonNull Context context) {
        List<ShortcutInfoCompat> shortcutInfos = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED);
        List<ShortcutItem> shortcutItems = new ArrayList<>(shortcutInfos.size());
        for (ShortcutInfoCompat item : shortcutInfos) {
            shortcutItems.add(new ShortcutItem(item));
        }
        return shortcutItems;
    }

    public static void createShortcut(@NonNull Context context, @NonNull ShortcutItem shortcutItem) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.error_creating_shortcut)
                    .setMessage(context.getString(R.string.error_creating_shortcut_description))
                    .setPositiveButton(context.getString(android.R.string.ok), null)
                    .create();
            dialog.setOnShowListener(d -> {
                Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (pos != null) {
                    pos.setTextColor(ContextCompat.getColor(context, R.color.fb_blue));
                    pos.setTypeface(null, Typeface.BOLD);
                }
            });
            dialog.show();
            return;
        }
        ShortcutManagerCompat.requestPinShortcut(context, shortcutItem.toShortcutInfo(context), null);
    }

    public static void updateShortcuts(@NonNull Context context, @NonNull List<ShortcutItem> shortcutItems) {
        List<ShortcutInfoCompat> shortcutInfos = new ArrayList<>();
        for (ShortcutItem shortcutItem : shortcutItems) {
            shortcutInfos.add(shortcutItem.toShortcutInfo(context));
        }
        ShortcutManagerCompat.updateShortcuts(context, shortcutInfos);
    }

    public static void displayShortcutTypeChooserDialog(@NonNull FragmentActivity context, @NonNull ActionItem actionItem) {
        List<ShortcutItem> shortcutItems = getShortcutItems(context);
        if (shortcutItems.isEmpty()) {
            // No shortcut exists
            displayNewShortcutCreatorDialog(context, actionItem);
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setItems(R.array.shortcut_choices, (d, which) -> {
                    if (which == 0) {
                        // Create a new shortcut
                        displayNewShortcutCreatorDialog(context, actionItem);
                    } else {
                        // Add to one or more existing shortcuts
                        displayExistingShortcutChooserDialog(context, actionItem, shortcutItems);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) {
                neg.setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary));
            }
        });
        dialog.show();
    }

    private static void displayNewShortcutCreatorDialog(@NonNull FragmentActivity context, @NonNull ActionItem actionItem) {
        CreateShortcutDialogFragment fragment = CreateShortcutDialogFragment.getInstance(actionItem);
        fragment.show(context.getSupportFragmentManager(), CreateShortcutDialogFragment.TAG);
    }

    private static void displayExistingShortcutChooserDialog(@NonNull FragmentActivity context,
                                                             @NonNull ActionItem actionItem,
                                                             @NonNull List<ShortcutItem> shortcutItems) {
        CharSequence[] titles = new CharSequence[shortcutItems.size()];
        boolean[] choices = new boolean[titles.length];
        for (int i = 0; i < titles.length; ++i) {
            titles[i] = shortcutItems.get(i).name;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.select_shortcuts)
                .setMultiChoiceItems(titles, null, (d, which, isChecked) -> choices[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    List<ShortcutItem> selectedItems = new ArrayList<>(choices.length);
                    for (int i = 0; i < choices.length; ++i) {
                        if (choices[i]) {
                            ShortcutItem item = shortcutItems.get(i);
                            item.addActionItem(actionItem);
                            selectedItems.add(item);
                        }
                    }
                    ShortcutUtils.updateShortcuts(context.getApplicationContext(), selectedItems);
                })
                .create();
        dialog.setOnShowListener(d -> {
            Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setTextColor(ContextCompat.getColor(context, R.color.fb_blue));
                pos.setTypeface(null, Typeface.BOLD);
            }
            Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) {
                neg.setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary));
            }
        });
        dialog.show();
    }
}
