package heartguard.heartguard.main;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

final class DomainRuleDialog {
    static void show(Context context, String packageName, String address, boolean blocked) {
        View view = LayoutInflater.from(context).inflate(R.layout.allowhost, null, false);
        EditText input = view.findViewById(R.id.allow_new_access_daddr);
        EditText packageInput = view.findViewById(R.id.allow_new_access_package_name);
        TextView title = view.findViewById(R.id.allow_new_access_title);
        TextView scope = view.findViewById(R.id.allow_new_access_package_label);
        input.setText(address);
        input.setHint(R.string.domain_hint);
        packageInput.setText(packageName);
        packageInput.setVisibility(View.GONE);
        title.setText(blocked ? R.string.title_block_app : packageName.isEmpty()
                ? R.string.title_allow_globally : R.string.title_allow);
        scope.setText(packageName.isEmpty() ? context.getString(R.string.domain_scope_all)
                : packageName + (blocked ? "\n\n" + context.getString(R.string.block_app_description) : ""));
        AlertDialog dialog = new AlertDialog.Builder(context).setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                String host = DomainInput.normalize(input.getText().toString());
                String rule = (blocked ? "block " : "allow ")
                        + (packageName.isEmpty() ? "" : "package:" + packageName + " ")
                        + DomainInput.destination(host);
                RulesManager.getInstance(context).queueRuleText(context, rule);
                Toast.makeText(context, R.string.rule_queued, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (IllegalArgumentException invalid) {
                input.setError(context.getString(R.string.domain_error));
            }
        }));
        dialog.show();
    }
}
