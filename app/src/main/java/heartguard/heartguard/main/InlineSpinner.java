package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;

/** Size the duration picker for its widest unit; keep the caret fixed in every state. */
public class InlineSpinner extends AppCompatSpinner {
    private final Drawable arrow;

    public InlineSpinner(Context context, AttributeSet attributes) {
        super(context, attributes);
        setBackground(selectionBackground());
        setPaddingRelative(0, 0, CompactUi.dp(context, 32), 0);
        arrow = AppCompatResources.getDrawable(context, R.drawable.ic_dropdown_compact).mutate();
        DrawableCompat.setTint(arrow, CompactUi.color(context, android.R.attr.textColorSecondary));
    }

    Drawable selectionBackground() {
        GradientDrawable highlight = new GradientDrawable();
        highlight.setColor(ColorUtils.setAlphaComponent(CompactUi.color(getContext(), android.R.attr.textColorPrimary), 24));
        highlight.setCornerRadius(CompactUi.dp(getContext(), 4));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_activated}, highlight);
        background.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return background;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int labelWidth = 0;
        if (getAdapter() != null) {
            for (int i = 0; i < getAdapter().getCount(); i++) {
                View item = getAdapter().getView(i, null, this);
                if (!(item instanceof TextView)) continue;
                TextView label = (TextView) item;
                Object value = getAdapter().getItem(i);
                if (value instanceof RuleDelay.Unit) {
                    for (int quantity : new int[]{1, 2}) labelWidth = Math.max(labelWidth,
                            (int) Math.ceil(label.getPaint().measureText(getResources().getQuantityString(((RuleDelay.Unit) value).label, quantity))));
                } else labelWidth = Math.max(labelWidth, (int) Math.ceil(label.getPaint().measureText(label.getText().toString())));
            }
        }
        int width = labelWidth + CompactUi.dp(getContext(), 48);
        super.onMeasure(MeasureSpec.makeMeasureSpec(resolveSize(width, widthSpec), MeasureSpec.EXACTLY), heightSpec);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int size = CompactUi.dp(getContext(), 24);
        int gap = CompactUi.dp(getContext(), 4);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int left = rtl ? gap : getWidth() - size - gap;
        int top = (getHeight() - size) / 2;
        arrow.setBounds(left, top, left + size, top + size);
        arrow.setAlpha(isEnabled() ? 255 : 100);
        int saved = canvas.save();
        if (isActivated()) canvas.rotate(180, left + size / 2f, top + size / 2f);
        arrow.draw(canvas);
        canvas.restoreToCount(saved);
    }
}
