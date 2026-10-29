package heartguard.heartguard.main;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.TooltipCompat;
import androidx.preference.PreferenceManager;

final class DomainFilterDialog {
    interface Apply { void apply(DomainFilters filters); }

    static void show(Context context, DomainFilters current, Apply apply) {
        show(context, current, apply, true);
    }

    static void show(Context context, DomainFilters current, Apply apply, boolean allowDefaults) {
        View view = LayoutInflater.from(context).inflate(R.layout.domain_filters, null);
        Spinner allowed = view.findViewById(R.id.domain_filter_allowed);
        Spinner blocked = view.findViewById(R.id.domain_filter_blocked);
        allowed.setSelection(current.allowed);
        blocked.setSelection(current.blocked);
        CompactUi.actionButton(view.findViewById(R.id.domain_filter_default));
        CompactUi.dismissButton(view.findViewById(R.id.domain_filter_reset));
        view.findViewById(R.id.domain_filter_default).setVisibility(allowDefaults ? View.VISIBLE : View.GONE);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setCustomTitle(SortDialog.title(context, R.string.domain_filters))
                .setView(view).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_apply, (window, which) -> apply.apply(
                        new DomainFilters(allowed.getSelectedItemPosition(), blocked.getSelectedItemPosition())))
                .create();
        view.findViewById(R.id.domain_filter_default).setOnClickListener(button -> {
            DomainFilters selection = new DomainFilters(allowed.getSelectedItemPosition(), blocked.getSelectedItemPosition());
            selection.saveDefaults(PreferenceManager.getDefaultSharedPreferences(context));
            apply.apply(selection);
            Toast.makeText(context, R.string.list_filter_default_saved, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        view.findViewById(R.id.domain_filter_reset).setOnClickListener(button -> {
            DomainFilters defaults = DomainFilters.defaults(PreferenceManager.getDefaultSharedPreferences(context));
            allowed.setSelection(defaults.allowed);
            blocked.setSelection(defaults.blocked);
        });
        dialog.show();
        DialogSurface.apply(dialog);
    }

    static void bind(ImageButton button, DomainFilters filters) {
        Context context = button.getContext();
        button.setSelected(filters.active());
        button.setImageTintList(ColorStateList.valueOf(CompactUi.color(context,
                filters.active() ? R.attr.colorActionPositive : android.R.attr.textColorSecondary)));
        String summary = context.getString(R.string.domain_filter_summary,
                context.getResources().getStringArray(R.array.domain_allowed_choices)[filters.allowed],
                context.getResources().getStringArray(R.array.domain_blocked_choices)[filters.blocked]);
        button.setContentDescription(context.getString(R.string.domain_filters) + ". " + summary);
        TooltipCompat.setTooltipText(button, summary);
    }
}
