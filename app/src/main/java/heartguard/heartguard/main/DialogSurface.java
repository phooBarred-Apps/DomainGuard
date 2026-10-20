package heartguard.heartguard.main;

import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;

final class DialogSurface {
    static void apply(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        float density = dialog.getContext().getResources().getDisplayMetrics().density;
        TypedValue color = new TypedValue();
        dialog.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, color, true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color.data);
        background.setCornerRadius(20 * density);
        window.setBackgroundDrawable(background);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.45f);
        int screenWidth = dialog.getContext().getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min((int) (560 * density), screenWidth - (int) (32 * density)),
                WindowManager.LayoutParams.WRAP_CONTENT);
    }
}
