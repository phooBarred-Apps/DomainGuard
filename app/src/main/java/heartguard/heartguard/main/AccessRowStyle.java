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

    /** The timestamp's extra symbol is the recorded result, only when the current rule differs. */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Touch listener only captures identity; TextView handles performClick.
    void bindHistory(AccessEntry entry, TextView time) {
        Context context = time.getContext();
        boolean different = entry.allowed >= 0 && entry.block >= 0
                && (entry.allowed > 0) != (entry.block == 0);
        android.graphics.drawable.Drawable result = different
                ? androidx.appcompat.content.res.AppCompatResources.getDrawable(context,
                        entry.allowed > 0 ? R.drawable.host_allowed : R.drawable.host_blocked) : null;
        if (result != null) result.setBounds(0, 0, CompactUi.dp(context, 16), CompactUi.dp(context, 16));
        time.setCompoundDrawablesRelative(null, null, result, null);
        time.setCompoundDrawablePadding(CompactUi.dp(context, 4));
        HistoryTap tap = time.getTag() instanceof HistoryTap ? (HistoryTap) time.getTag() : new HistoryTap();
        tap.entry = entry;
        time.setTag(tap);
        time.setOnTouchListener(tap);
        time.setOnClickListener(tap);
        time.setClickable(different);
        time.setFocusable(different);
        // Keep the symbol small but let the timestamp's padded area receive the tap.
        time.post(() -> {
            android.view.View parent = (android.view.View) time.getParent();
            if (!time.isClickable()) { parent.setTouchDelegate(null); return; }
            android.graphics.Rect target = new android.graphics.Rect();
            time.getHitRect(target);
            int extra = Math.max(0, (CompactUi.dp(context, 48) - target.height()) / 2);
            target.top = Math.max(0, target.top - extra);
            target.bottom = Math.min(parent.getHeight(), target.bottom + extra);
            parent.setTouchDelegate(new android.view.TouchDelegate(target, time));
        });
        String description = DomainTimestamp.full(entry.time);
        if (different) description += ". " + explanation(context, entry);
        time.setContentDescription(description);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(time, different ? explanation(context, entry) : null);
    }

    private static String explanation(Context context, AccessEntry entry) {
        return context.getString(entry.allowed > 0 ? R.string.domain_allowed_then : R.string.domain_blocked_then);
    }

    private static final class HistoryTap implements android.view.View.OnTouchListener, android.view.View.OnClickListener {
        AccessEntry entry, pressed;
        @android.annotation.SuppressLint("ClickableViewAccessibility") // Return false to preserve native click/accessibility handling.
        public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) pressed = entry;
            else if (event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) pressed = null;
            return false;
        }
        public void onClick(android.view.View view) {
            AccessEntry selected = pressed == null ? entry : pressed;
            pressed = null;
            if (selected != null) CompactUi.help(view.getContext(), R.string.domain_recorded_result,
                    selected.address + "\n" + DomainTimestamp.full(selected.time) + "\n\n"
                            + explanation(view.getContext(), selected));
        }
    }
}
