package heartguard.heartguard.main;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/** Frameless tabs; domain tools wrap only when labels and touch targets need more room. */
public class AppPanelTabs extends ViewGroup {
    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int tabHeight;
    private boolean toolsBelow;

    public AppPanelTabs(Context context, AttributeSet attributes) {
        super(context, attributes);
        int background = CompactUi.color(context, android.R.attr.colorBackground);
        int color = CompactUi.color(context, R.attr.colorPrimary);
        int foreground = CompactUi.color(context, android.R.attr.textColorPrimary);
        for (int i = 0; i < 10 && ColorUtils.calculateContrast(color, background) < 3; i++)
            color = ColorUtils.blendARGB(color, ColorUtils.compositeColors(foreground, background), 0.2f);
        indicator.setColor(color);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        ColorStateList colors = new ColorStateList(new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                new int[]{CompactUi.color(getContext(), android.R.attr.textColorPrimary),
                        CompactUi.color(getContext(), android.R.attr.textColorSecondary)});
        for (int i = 0; i < 2; i++) {
            TextView tab = (TextView) getChildAt(i);
            tab.setTextColor(colors);
            ViewCompat.setAccessibilityDelegate(tab, new AccessibilityDelegateCompat() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.app.ActionBar$Tab");
                    info.setSelected(host.isSelected());
                }
            });
        }
    }

    static void select(View app, boolean info) {
        View domains = app.findViewById(R.id.llFilter);
        if (info) ((DomainListView) app.findViewById(R.id.lvAccess)).stopScroll();
        if (info && domains.hasFocus()) {
            domains.clearFocus();
            InputMethodManager keyboard = (InputMethodManager) app.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(app.getWindowToken(), 0);
        }
        app.findViewById(R.id.appOptions).setVisibility(info ? VISIBLE : GONE);
        boolean canFilter = Util.canFilter(app.getContext());
        domains.setVisibility(!info && canFilter ? VISIBLE : GONE);
        app.findViewById(R.id.domainControls).setVisibility(!info && canFilter ? VISIBLE : INVISIBLE);
        app.findViewById(R.id.appDomainsButton).setSelected(!info);
        app.findViewById(R.id.appOptionsButton).setSelected(info);
        app.findViewById(R.id.appPanelTabs).invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        View first = getChildAt(0), second = getChildAt(1), tools = getChildAt(2);
        first.measure(free, free);
        second.measure(free, free);
        tools.measure(free, free);
        if (first.getMeasuredWidth() + second.getMeasuredWidth() > width) {
            first.measure(MeasureSpec.makeMeasureSpec(width * 2 / 5, MeasureSpec.AT_MOST), free);
            second.measure(MeasureSpec.makeMeasureSpec(width - first.getMeasuredWidth(), MeasureSpec.AT_MOST), free);
        }
        tabHeight = Math.max(first.getMeasuredHeight(), second.getMeasuredHeight());
        toolsBelow = first.getMeasuredWidth() + second.getMeasuredWidth() + tools.getMeasuredWidth() > width;
        int height = toolsBelow ? tabHeight + (tools.getVisibility() == VISIBLE ? tools.getMeasuredHeight() : 0)
                : Math.max(tabHeight, tools.getMeasuredHeight());
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View first = getChildAt(0), second = getChildAt(1), tools = getChildAt(2);
        boolean rtl = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL;
        int width = right - left;
        int firstStart = rtl ? width - first.getMeasuredWidth() : 0;
        int secondStart = rtl ? firstStart - second.getMeasuredWidth() : first.getMeasuredWidth();
        first.layout(firstStart, 0, firstStart + first.getMeasuredWidth(), tabHeight);
        second.layout(secondStart, 0, secondStart + second.getMeasuredWidth(), tabHeight);
        int toolsStart = rtl ? 0 : width - tools.getMeasuredWidth();
        int toolsTop = toolsBelow ? tabHeight : Math.max(0, (tabHeight - tools.getMeasuredHeight()) / 2);
        tools.layout(toolsStart, toolsTop, toolsStart + tools.getMeasuredWidth(), toolsTop + tools.getMeasuredHeight());
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        View selected = getChildAt(getChildAt(1).isSelected() ? 1 : 0);
        canvas.drawRect(selected.getLeft(), tabHeight - CompactUi.dp(getContext(), 2),
                selected.getRight(), tabHeight, indicator);
    }
}
