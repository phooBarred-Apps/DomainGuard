package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;

/** One delay presentation for allow-app and global pause/turn-off confirmations. */
final class DelayNotice extends LinearLayout {
    private final TextView timing;
    private final TextView explanation;
    private final ImageView chevron;
    private final View row;
    private boolean expanded;

    DelayNotice(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(CompactUi.dp(context, 12), 0, CompactUi.dp(context, 12), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(surface(context));
        background.setCornerRadius(CompactUi.dp(context, 8));
        setBackground(background);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(CompactUi.dp(context, 48));
        header.setId(R.id.delay_notice_toggle);
        CompactUi.frameless(header);
        ImageView clock = new ImageView(context);
        clock.setImageResource(R.drawable.ic_delay_clock);
        header.addView(clock, new LayoutParams(CompactUi.dp(context, 24), CompactUi.dp(context, 24)));
        timing = new TextView(context);
        timing.setId(R.id.delay_notice_timing);
        timing.setTextAppearance(context, R.style.TextSmall);
        timing.setPadding(CompactUi.dp(context, 8), CompactUi.dp(context, 8), CompactUi.dp(context, 8), CompactUi.dp(context, 8));
        header.addView(timing, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_dropdown_compact);
        header.addView(chevron, new LayoutParams(CompactUi.dp(context, 20), CompactUi.dp(context, 20)));
        addView(header);
        row = header;
        explanation = new TextView(context);
        explanation.setId(R.id.delay_notice_explanation);
        explanation.setTextAppearance(context, R.style.TextSmall);
        explanation.setPadding(CompactUi.dp(context, 32), 0, 0, CompactUi.dp(context, 12));
        addView(explanation);
        row.setOnClickListener(view -> { expanded = !expanded; render(); });
        render();
    }

    static int surface(Context context) {
        int background = CompactUi.color(context, android.R.attr.colorBackground);
        return ColorUtils.blendARGB(background, ColorUtils.calculateLuminance(background) < .5 ? Color.WHITE : Color.BLACK, .06f);
    }

    void setDelay(int seconds, String detail) {
        timing.setText(seconds == 0 ? getContext().getString(R.string.delay_notice_now)
                : getContext().getString(R.string.domain_delay, RuleDelay.duration(getContext(), seconds)));
        explanation.setText(seconds == 0 ? getContext().getString(R.string.delay_notice_immediate_explanation) : detail);
    }

    private void render() {
        explanation.setVisibility(expanded ? VISIBLE : GONE);
        chevron.setRotation(expanded ? 180 : 0);
        row.setContentDescription(getContext().getString(expanded ? R.string.delay_notice_collapse : R.string.delay_notice_expand));
    }
}
