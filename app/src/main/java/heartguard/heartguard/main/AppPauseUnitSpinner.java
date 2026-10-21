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
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.SpinnerAdapter;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;

/** Keep the unit menu below its field instead of covering the selected value. */
public class AppPauseUnitSpinner extends AppCompatSpinner {
    private ListPopupWindow popup;
    private final Drawable arrow;

    public AppPauseUnitSpinner(Context context, AttributeSet attributes) {
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

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int size = CompactUi.dp(getContext(), 24);
        int inset = CompactUi.dp(getContext(), 4);
        int left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? inset : getWidth() - size - inset;
        int top = (getHeight() - size) / 2;
        arrow.setBounds(left, top, left + size, top + size);
        arrow.setAlpha(isEnabled() ? 255 : 100);
        int saved = canvas.save();
        if (isActivated()) canvas.rotate(180, left + size / 2f, top + size / 2f);
        arrow.draw(canvas);
        canvas.restoreToCount(saved);
    }

    private void showOpenState(boolean open) {
        setActivated(open);
        invalidate();
    }

    @Override public boolean performClick() {
        if (!isEnabled()) return false;
        if (popup != null && popup.isShowing()) { popup.dismiss(); return true; }
        popup = new ListPopupWindow(getContext(), null, 0, R.style.AppPauseUnitMenu);
        popup.setAnchorView(this);
        popup.setWidth(getWidth());
        popup.setHorizontalOffset(0);
        popup.setVerticalOffset(0);
        popup.setModal(true);
        popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
        SpinnerAdapter units = getAdapter();
        popup.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return units.getCount(); }
            @Override public Object getItem(int position) { return units.getItem(position); }
            @Override public long getItemId(int position) { return units.getItemId(position); }
            @Override public View getView(int position, View view, ViewGroup parent) {
                View option = units.getDropDownView(position, view, parent);
                if (option.getBackground() == null) option.setBackground(selectionBackground());
                option.setActivated(position == getSelectedItemPosition());
                return option;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> { setSelection(position); popup.dismiss(); });
        popup.setOnDismissListener(() -> showOpenState(false));
        popup.show();
        showOpenState(true);
        popup.getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        popup.getListView().setItemChecked(getSelectedItemPosition(), true);
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        if (popup != null) popup.dismiss();
        super.onDetachedFromWindow();
    }
}
