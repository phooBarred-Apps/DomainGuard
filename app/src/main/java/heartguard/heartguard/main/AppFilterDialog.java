package heartguard.heartguard.main;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;
import androidx.appcompat.app.AlertDialog;

final class AppFilterDialog {
    static void show(Context context, AppListOptions options, Runnable apply, Runnable reset) {
        View content = LayoutInflater.from(context).inflate(R.layout.app_list_filters, null);
        Spinner access = content.findViewById(R.id.filter_access);
        Spinner activity = content.findViewById(R.id.filter_activity);
        Spinner type = content.findViewById(R.id.filter_type);
        CheckBox noInternet = content.findViewById(R.id.filter_no_internet);
        CheckBox disabled = content.findViewById(R.id.filter_disabled);
        access.setSelection(options.access);
        activity.setSelection(options.activity);
        type.setSelection(options.type);
        noInternet.setChecked(options.includeNoInternet);
        disabled.setChecked(options.includeDisabled);
        AlertDialog dialog = new AlertDialog.Builder(context).setCustomTitle(SortDialog.title(context, R.string.list_filters))
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.list_reset_filters, (window, which) -> reset.run())
                .setPositiveButton(R.string.list_apply, (window, which) -> {
                    options.access = access.getSelectedItemPosition();
                    options.activity = activity.getSelectedItemPosition();
                    options.type = type.getSelectedItemPosition();
                    options.includeNoInternet = noInternet.isChecked();
                    options.includeDisabled = disabled.isChecked();
                    apply.run();
                }).create();
        dialog.show();
        DialogSurface.apply(dialog);
    }
}
