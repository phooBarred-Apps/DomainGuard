package heartguard.heartguard.main;

import android.content.Context;
import android.widget.ImageView;
import android.widget.TextView;

/** Keep rule indicators and traffic-result colors consistent across access views. */
final class AccessRowStyle {
    private final int neutral, allowed, blocked, pending;

    AccessRowStyle(Context context) {
        neutral = CompactUi.color(context, android.R.attr.textColorSecondary);
        allowed = CompactUi.color(context, R.attr.colorOn);
        blocked = CompactUi.color(context, R.attr.colorOff);
        pending = CompactUi.color(context, R.attr.colorPending);
    }

    void bind(AccessEntry entry, ImageView icon, TextView domain) {
        Context context = icon.getContext();
        icon.setContentDescription(null);
        if (entry.globalScope != GlobalRuleIndicators.LOCAL) {
            boolean globalBlock = entry.globalScope == GlobalRuleIndicators.BLOCKED;
            icon.setImageResource(globalBlock ? R.drawable.host_global_blocked : R.drawable.host_global_allowed);
            icon.setContentDescription(context.getString(globalBlock
                    ? R.string.domain_globally_blocked : R.string.domain_globally_allowed));
        } else if (entry.block < 0) {
            icon.setImageDrawable(null);
        } else {
            icon.setImageResource(entry.block > 0 ? R.drawable.host_blocked : R.drawable.host_allowed);
        }
        domain.setTextColor(entry.allowed < 0 ? neutral : entry.allowed > 0 ? allowed
                : entry.pending > 0 ? pending : blocked);
    }
}
