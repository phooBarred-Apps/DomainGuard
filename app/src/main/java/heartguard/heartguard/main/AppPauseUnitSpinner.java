package heartguard.heartguard.main;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.SpinnerAdapter;
import androidx.appcompat.widget.ListPopupWindow;

/** Keep the unit menu below its field instead of covering the selected value. */
public class AppPauseUnitSpinner extends InlineSpinner {
    private ListPopupWindow popup;

    public AppPauseUnitSpinner(Context context, AttributeSet attributes) {
        super(context, attributes);
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
