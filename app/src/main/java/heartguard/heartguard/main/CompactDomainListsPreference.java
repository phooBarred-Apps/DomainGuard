package heartguard.heartguard.main;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import androidx.appcompat.widget.SwitchCompat;

/** The switch changes compact mode; the preference row opens its height settings. */
public class CompactDomainListsPreference extends Preference {
    public CompactDomainListsPreference(Context context, AttributeSet attributes) {
        super(context, attributes);
        setWidgetLayoutResource(R.layout.preference_compact_domains_switch);
    }

    @Override protected void onBindView(View view) {
        super.onBindView(view);
        view.setOnClickListener(row -> {
            OnPreferenceClickListener listener = getOnPreferenceClickListener();
            if (listener != null) listener.onPreferenceClick(this);
        });
        SwitchCompat toggle = view.findViewById(R.id.compact_domains_toggle);
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(getPersistedBoolean(false));
        toggle.setContentDescription(getTitle());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (callChangeListener(checked)) persistBoolean(checked);
            else button.setChecked(getPersistedBoolean(false));
        });
    }
}
