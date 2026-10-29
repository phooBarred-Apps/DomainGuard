package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.ColorUtils;

/** Optional height limit; gestures inside an overflowing list belong to its domains. */
public class DomainListView extends RecyclerView {
    private int maximumRows;
    private boolean ownsGesture;

    public DomainListView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setNestedScrollingEnabled(false);
        setScrollbarFadingEnabled(false);
        setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_RIGHT);
        setSurfaceColor(CompactUi.color(context, android.R.attr.colorBackground));
    }

    /** Keep the domain surface distinct while retaining the enclosing app's theme/tint. */
    void setSurfaceColor(int parentColor) {
        parentColor = ColorUtils.compositeColors(parentColor,
                CompactUi.color(getContext(), android.R.attr.colorBackground));
        int tint = ColorUtils.calculateLuminance(parentColor) < 0.5 ? Color.WHITE : Color.BLACK;
        setBackgroundColor(ColorUtils.blendARGB(parentColor, tint, 0.06f));
    }

    void setMaximumRows(int rows) {
        if (maximumRows == rows) return;
        maximumRows = rows;
        setVerticalScrollBarEnabled(rows > 0);
        requestLayout();
    }

    int maximumHeight() {
        return Math.round(CompactUi.dp(getContext(), 48) * maximumRows
                * Math.max(1f, getResources().getConfiguration().fontScale));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (maximumRows > 0) {
            int limit = maximumHeight();
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
                limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
            heightSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN)
            ownsGesture = maximumRows > 0 && (canScrollVertically(-1) || canScrollVertically(1));
        // Keep an overflowing list's whole gesture, including at either edge. Side gutters remain outside this view.
        if (ownsGesture && getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(true);
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            ownsGesture = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }
}
