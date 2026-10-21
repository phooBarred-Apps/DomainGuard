package heartguard.heartguard.main;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

final class AppFilterDialog {
    static void show(Context context, AppListOptions options, Runnable apply, Runnable reset) {
        View content = LayoutInflater.from(context).inflate(R.layout.app_list_filters, null);
        Spinner access = content.findViewById(R.id.filter_access);
        Spinner activity = content.findViewById(R.id.filter_activity);
        Spinner type = content.findViewById(R.id.filter_type);
        SwitchCompat noInternet = content.findViewById(R.id.filter_no_internet);
        SwitchCompat disabled = content.findViewById(R.id.filter_disabled);
        CompactUi.actionButton(content.findViewById(R.id.filter_default));
        access.setSelection(options.access);
        activity.setSelection(options.activity);
        type.setSelection(options.type);
        noInternet.setChecked(options.includeNoInternet);
        disabled.setChecked(options.includeDisabled);
        Runnable applySelection = () -> {
            options.access = access.getSelectedItemPosition();
            options.activity = activity.getSelectedItemPosition();
            options.type = type.getSelectedItemPosition();
            options.includeNoInternet = noInternet.isChecked();
            options.includeDisabled = disabled.isChecked();
            apply.run();
        };
        AlertDialog dialog = new AlertDialog.Builder(context).setCustomTitle(SortDialog.title(context, R.string.list_filters))
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_apply, (window, which) -> applySelection.run()).create();
        content.findViewById(R.id.filter_default).setOnClickListener(view -> {
            applySelection.run();
            options.saveFilterDefaults(PreferenceManager.getDefaultSharedPreferences(context));
            Toast.makeText(context, R.string.list_filter_default_saved, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        content.findViewById(R.id.filter_reset).setOnClickListener(view -> {
            reset.run();
            dialog.dismiss();
        });
        dialog.show();
        DialogSurface.apply(dialog);
    }
}
