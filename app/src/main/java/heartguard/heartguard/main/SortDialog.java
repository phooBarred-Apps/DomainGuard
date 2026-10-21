package heartguard.heartguard.main;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import java.util.LinkedHashSet;

final class SortDialog {
    interface Selection { void apply(String sort); }

    static void show(Context context, int title, String[] choices, String current,
                     String defaultKey, Selection selection) {
        String[] chosen = {ListSort.valid(current, choices)};
        ScrollView scroll = new ScrollView(context);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(CompactUi.dp(context, 12), 0, CompactUi.dp(context, 12), 0);
        scroll.addView(content);
        LinkedHashSet<String> criteria = new LinkedHashSet<>();
        for (String choice : choices) criteria.add(ListSort.criterion(choice));
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            content.removeAllViews();
            for (String criterion : criteria) {
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                RadioButton radio = new RadioButton(context);
                int label = ListSort.NAME.equals(criterion)
                        ? (ListSort.DOMAIN_DEFAULT.equals(defaultKey) ? R.string.sort_criterion_domain : R.string.sort_criterion_name)
                        : ListSort.RECENT.equals(criterion) ? R.string.sort_criterion_recent
                        : ListSort.UID.equals(criterion) ? R.string.sort_criterion_uid : R.string.sort_criterion_access;
                radio.setText(label);
                radio.setMinHeight(CompactUi.dp(context, 48));
                boolean active = criterion.equals(ListSort.criterion(chosen[0]));
                radio.setChecked(active);
                radio.setOnClickListener(v -> {
                    if (!criterion.equals(ListSort.criterion(chosen[0]))) {
                        chosen[0] = criterion;
                        selection.apply(chosen[0]);
                        render[0].run();
                    }
                });
                row.addView(radio, new LinearLayout.LayoutParams(0, -2, 1));
                if (active) {
                    int first = ListSort.NAME.equals(criterion) ? R.string.sort_az
                            : ListSort.RECENT.equals(criterion) ? R.string.sort_newest
                            : ListSort.UID.equals(criterion) ? R.string.sort_lowest : R.string.list_sort_blocked;
                    int second = ListSort.NAME.equals(criterion) ? R.string.sort_za
                            : ListSort.RECENT.equals(criterion) ? R.string.sort_oldest_short
                            : ListSort.UID.equals(criterion) ? R.string.sort_highest_short : R.string.list_sort_allowed;
                    for (int direction = 0; direction < 2; direction++) {
                        String value = direction == 0 ? criterion : ListSort.reverse(criterion);
                        Button button = CompactUi.button(context, direction == 0 ? first : second, v -> {
                            chosen[0] = value;
                            selection.apply(value);
                            render[0].run();
                        });
                        button.setMinWidth(0);
                        button.setMinimumWidth(0);
                        button.setTextSize(12);
                        button.setPadding(CompactUi.dp(context, 6), 0, CompactUi.dp(context, 6), 0);
                        button.setSelected(value.equals(chosen[0]));
                        button.setTypeface(null, value.equals(chosen[0]) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                        row.addView(button);
                    }
                }
                content.addView(row);
            }
            content.addView(CompactUi.button(context, R.string.list_set_default, v -> {
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString(defaultKey, chosen[0]).apply();
                Toast.makeText(context, R.string.list_default_saved, Toast.LENGTH_SHORT).show();
            }), new LinearLayout.LayoutParams(-2, -2));
        };
        render[0].run();
        AlertDialog dialog = new AlertDialog.Builder(context).setCustomTitle(title(context, title))
                .setView(scroll).setPositiveButton(R.string.list_done, null).create();
        dialog.show();
        DialogSurface.apply(dialog);
    }

    static TextView title(Context context, int resource) {
        TextView title = new TextView(context);
        title.setText(resource);
        title.setTextAppearance(context, R.style.TextLarge);
        title.setPadding(CompactUi.dp(context, 24), CompactUi.dp(context, 20), CompactUi.dp(context, 24), CompactUi.dp(context, 12));
        return title;
    }
}
