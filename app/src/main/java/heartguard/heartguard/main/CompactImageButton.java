package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatImageButton;

/** Keep the icon's feedback smaller than its accessible touch target. */
public class CompactImageButton extends AppCompatImageButton {
    public CompactImageButton(Context context, AttributeSet attributes) {
        super(context, attributes);
        CompactUi.frameless(this);
    }

    void setSelectedColor(int color) {
        if (getBackground() instanceof Feedback)
            ((Feedback) getBackground()).setSelectedColor(color);
    }

    static final class Feedback extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float touchRadius;
        private final float selectedRadius;
        private final int touchColor;
        private int selectedColor;
        private boolean touching;
        private boolean selected;
        private boolean enabled;
        private int alpha = 255;

        Feedback(Context context) {
            touchRadius = CompactUi.dp(context, 36) / 2f;
            selectedRadius = CompactUi.dp(context, 32) / 2f;
            touchColor = CompactUi.color(context, android.R.attr.textColorPrimary);
            selectedColor = CompactUi.color(context, R.attr.colorActionPositive);
        }

        void setSelectedColor(int color) { selectedColor = color; invalidateSelf(); }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] states) {
            touching = false;
            selected = false;
            enabled = false;
            for (int state : states) {
                touching |= state == android.R.attr.state_pressed || state == android.R.attr.state_focused
                        || state == android.R.attr.state_hovered;
                selected |= state == android.R.attr.state_selected;
                enabled |= state == android.R.attr.state_enabled;
            }
            invalidateSelf();
            return true;
        }

        @Override public void draw(Canvas canvas) {
            boolean feedback = touching && enabled;
            if (!feedback && !selected) return;
            paint.setColor(feedback ? touchColor : selectedColor);
            paint.setAlpha(Math.round((feedback ? 31 : 40) * (alpha / 255f) * (enabled ? 1f : 0.4f)));
            float radius = Math.min(feedback ? touchRadius : selectedRadius,
                    Math.min(getBounds().width(), getBounds().height()) / 2f);
            canvas.drawCircle(getBounds().exactCenterX(), getBounds().exactCenterY(), radius, paint);
        }

        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
