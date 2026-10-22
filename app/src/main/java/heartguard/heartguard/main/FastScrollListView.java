package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/** The raw editor's persistent edge scrollbar, adapted to a list of rule rows. */
public class FastScrollListView extends ListView {
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean dragging;
    private float dragOffset;

    public FastScrollListView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setVerticalScrollBarEnabled(false);
        thumb.setColor(0xff80cbc4);
    }

    private int viewport() { return Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom()); }
    private boolean overflows() { return canScrollVertically(-1) || canScrollVertically(1); }
    private float thumbHeight() {
        return Math.min(viewport(), Math.max(CompactUi.dp(getContext(), 36),
                (float) viewport() * computeVerticalScrollExtent() / Math.max(1, computeVerticalScrollRange())));
    }
    private float thumbTop() {
        int maximum = Math.max(1, computeVerticalScrollRange() - computeVerticalScrollExtent());
        return getPaddingTop() + (viewport() - thumbHeight())
                * Math.max(0, Math.min(maximum, computeVerticalScrollOffset())) / maximum;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!overflows()) return;
        float top = thumbTop();
        canvas.drawRoundRect(getWidth() - CompactUi.dp(getContext(), 4), top,
                getWidth(), top + thumbHeight(), CompactUi.dp(getContext(), 2), CompactUi.dp(getContext(), 2), thumb);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && overflows()
                && event.getX() >= getWidth() - CompactUi.dp(getContext(), 24)) {
            dragging = true;
            float top = thumbTop();
            dragOffset = event.getY() >= top && event.getY() <= top + thumbHeight()
                    ? event.getY() - top : thumbHeight() / 2;
            getParent().requestDisallowInterceptTouchEvent(true);
            // Stop any active fling before taking over the edge gesture.
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel);
            cancel.recycle();
        }
        if (!dragging) return super.onTouchEvent(event);
        if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            float fraction = (event.getY() - getPaddingTop() - dragOffset) / Math.max(1, viewport() - thumbHeight());
            fraction = Math.max(0, Math.min(1, fraction));
            float rows = fraction * (getCount() - 1);
            View first = getChildAt(0);
            int rowHeight = first == null ? 0 : first.getHeight() + getDividerHeight();
            setSelectionFromTop((int) rows, -Math.round((rows - (int) rows) * rowHeight));
            invalidate();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (event.getActionMasked() == MotionEvent.ACTION_UP)
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED);
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}
