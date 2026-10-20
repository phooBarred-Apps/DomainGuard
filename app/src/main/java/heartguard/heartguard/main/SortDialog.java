package heartguard.heartguard.main;

import android.content.Context;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

final class SortDialog {
    interface Selection { void apply(String sort); }

    static void show(Context context, int title, String[] choices, String current,
                     String defaultKey, Selection selection) {
        String[] labels = new String[choices.length];
        int selected = 0;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = context.getString(ListSort.label(choices[i]));
            if (choices[i].equals(current)) selected = i;
        }
        String[] chosen = {choices[selected]};
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setCustomTitle(title(context, title))
                .setSingleChoiceItems(labels, selected, (window, index) -> {
                    chosen[0] = choices[index];
                    selection.apply(chosen[0]);
                })
                .setNeutralButton(R.string.list_set_default, null)
                .setPositiveButton(R.string.list_done, null)
                .create();
        dialog.setOnShowListener(window -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(defaultKey, chosen[0]).apply();
            Toast.makeText(context, R.string.list_default_saved, Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
        DialogSurface.apply(dialog);
    }

    static TextView title(Context context, int resource) {
        TextView title = new TextView(context);
        title.setText(resource);
        title.setTextAppearance(context, R.style.TextLarge);
        title.setGravity(Gravity.CENTER);
        int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        title.setPadding(padding, padding, padding, padding / 2);
        return title;
    }
}
