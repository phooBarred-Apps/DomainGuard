package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatEditText;

/** A full-document editor with a persistent, draggable scrollbar at the right edge. */
public class FastScrollEditText extends AppCompatEditText {
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean dragging;
    private float dragOffset;

    public FastScrollEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        setVerticalScrollBarEnabled(false);
        thumb.setColor(0xff80cbc4);
    }

    private int viewport() { return Math.max(1, getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom()); }
    private int contentHeight() { return getLayout() == null ? 0 : getLayout().getHeight(); }
    private int maximum() { return Math.max(0, contentHeight() - viewport()); }
    private float thumbHeight() { return Math.min(viewport(), Math.max(CompactUi.dp(getContext(), 36), (float) viewport() * viewport() / Math.max(1, contentHeight()))); }
    private float thumbTop() { return getCompoundPaddingTop() + (viewport() - thumbHeight()) * Math.max(0, Math.min(maximum(), getScrollY())) / Math.max(1, maximum()); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (maximum() == 0) return;
        float top = getScrollY() + thumbTop();
        canvas.drawRoundRect(getScrollX() + getWidth() - CompactUi.dp(getContext(), 4), top,
                getScrollX() + getWidth(), top + thumbHeight(), CompactUi.dp(getContext(), 2), CompactUi.dp(getContext(), 2), thumb);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && maximum() > 0
                && event.getX() >= getWidth() - CompactUi.dp(getContext(), 24)) {
            dragging = true;
            float y = event.getY();
            dragOffset = y >= thumbTop() && y <= thumbTop() + thumbHeight() ? y - thumbTop() : thumbHeight() / 2;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!dragging) return super.onTouchEvent(event);
        float fraction = (event.getY() - getCompoundPaddingTop() - dragOffset) / Math.max(1, viewport() - thumbHeight());
        scrollTo(0, Math.round(Math.max(0, Math.min(1, fraction)) * maximum()));
        invalidate();
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (event.getActionMasked() == MotionEvent.ACTION_UP)
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED);
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    void revealLine(int line) {
        if (getLayout() != null) scrollTo(0, Math.min(maximum(), getLayout().getLineTop(line)));
    }

}
