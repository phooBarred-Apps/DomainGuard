package heartguard.heartguard.main;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.appcompat.widget.TooltipCompat;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

final class DomainRuleDialog {
    interface SaveGuard { boolean canSave(); }
    private static ParentDomains parentDomains;

    static void show(Context context, String packageName, String address, boolean blocked) {
        show(context, packageName, address, blocked, () -> true, () -> { });
    }

    static AlertDialog show(Context context, String packageName, String address, boolean blocked,
                            SaveGuard guard, Runnable onDismiss) {
        View view = LayoutInflater.from(context).inflate(R.layout.allowhost, null, false);
        EditText input = view.findViewById(R.id.allow_new_access_daddr);
        TextView title = view.findViewById(R.id.allow_new_access_title);
        TextView scope = view.findViewById(R.id.allow_new_access_package_label);
        TextView delay = view.findViewById(R.id.domain_action_delay);
        CompactImageButton global = view.findViewById(R.id.allow_new_access_global);
        int actionColor = CompactUi.color(context, blocked ? R.attr.colorActionDestructive : R.attr.colorActionPositive);
        global.setSelectedColor(actionColor);
        ImageButton parents = view.findViewById(R.id.domain_parent_choices);
        ImageView appIcon = view.findViewById(R.id.domain_scope_icon);
        TemporaryRuleControls temporary = new TemporaryRuleControls(view);
        boolean[] applyGlobally = {false};
        input.setText(address);
        input.clearFocus();
        title.setText(blocked ? R.string.domain_block_title : R.string.domain_allow_title);
        String app = appName(context, packageName);
        try { appIcon.setImageDrawable(context.getPackageManager().getApplicationIcon(packageName)); }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { appIcon.setImageResource(R.drawable.ic_globe_compact); }
        Runnable refresh = () -> {
            scope.setText(applyGlobally[0] ? context.getString(R.string.scope_all_apps) : app);
            appIcon.setVisibility(applyGlobally[0] ? View.GONE : View.VISIBLE);
            global.setSelected(applyGlobally[0]);
            global.setImageTintList(applyGlobally[0]
                    ? ColorStateList.valueOf(actionColor) : null);
            global.setContentDescription(context.getString(applyGlobally[0] ? R.string.scope_on : R.string.scope_off));
            TooltipCompat.setTooltipText(global, global.getContentDescription());
            parents.setEnabled(!parents(context, input.getText().toString()).isEmpty());
            parents.setAlpha(parents.isEnabled() ? 1f : 0.3f);
            int seconds = 0;
            try { seconds = RulesManager.getInstance(context).previewDelay(context,
                    rule(packageName, DomainInput.normalize(input.getText().toString()), blocked, applyGlobally[0])); }
            catch (IllegalArgumentException ignored) { }
            delay.setText(context.getString(R.string.domain_delay, RuleDelay.duration(context, seconds)));
            delay.setVisibility(seconds > 0 ? View.VISIBLE : View.GONE);
        };
        global.setOnClickListener(button -> { applyGlobally[0] = !applyGlobally[0]; input.setError(null); refresh.run(); });
        parents.setOnClickListener(button -> {
            List<String> choices = parents(context, input.getText().toString());
            View field = view.findViewById(R.id.domain_input_row);
            ListPopupWindow popup = new ListPopupWindow(context);
            popup.setAnchorView(field);
            popup.setWidth(field.getWidth());
            popup.setModal(true);
            popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
            popup.setAdapter(new android.widget.ArrayAdapter<>(context, android.R.layout.simple_list_item_1, choices));
            popup.setOnItemClickListener((list, row, position, id) -> {
                input.setText(choices.get(position));
                input.setSelection(input.length());
                input.clearFocus();
                view.findViewById(R.id.domain_parent_notice).setVisibility(View.VISIBLE);
                popup.dismiss();
            });
            popup.show();
        });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { refresh.run(); }
            public void afterTextChanged(Editable text) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(context).setView(view)
                .setPositiveButton(blocked ? R.string.title_block : R.string.title_allow, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        DatabaseHelper.RuleChangedListener listener = () -> view.post(refresh);
        dialog.setOnDismissListener(ignored -> {
            DatabaseHelper.getInstance(context).removeRuleChangedListener(listener);
            onDismiss.run();
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (!guard.canSave()) { dialog.dismiss(); return; }
            try {
                String host = DomainInput.normalize(input.getText().toString());
                String text = temporary.apply(rule(packageName, host, blocked, applyGlobally[0]));
                if (text == null) return;
                RulesManager.getInstance(context).queueRuleText(context, text);
                Toast.makeText(context, R.string.rule_queued, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (IllegalArgumentException invalid) {
                input.setError(context.getString(blocked && applyGlobally[0] ? R.string.blacklist_domain_error : R.string.domain_error));
            }
        }));
        dialog.show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        DialogSurface.apply(dialog);
        if (blocked) CompactUi.destructiveButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        DatabaseHelper.getInstance(context).addRuleChangedListener(listener);
        refresh.run();
        return dialog;
    }

    private static synchronized List<String> parents(Context context, String input) {
        if (parentDomains == null) {
            try (InputStreamReader source = new InputStreamReader(context.getResources().openRawResource(R.raw.public_suffix_list), StandardCharsets.UTF_8)) {
                parentDomains = new ParentDomains(source);
            } catch (java.io.IOException | IllegalArgumentException unavailable) { return Collections.emptyList(); }
        }
        return parentDomains.choices(input);
    }

    private static String rule(String packageName, String host, boolean blocked, boolean global) {
        if (blocked && global) {
            if (host.contains(":") || host.matches("[0-9.]+")) throw new IllegalArgumentException();
            return "blockdomain " + host;
        }
        return (blocked ? "block " : "allow ") + (global ? "" : "package:" + packageName + " ") + DomainInput.destination(host);
    }

    static String appName(Context context, String packageName) {
        try {
            android.content.pm.PackageManager packages = context.getPackageManager();
            return packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) { return packageName; }
    }
}
