package heartguard.heartguard.main;

import android.content.Context;
import android.text.InputFilter;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatEditText;

/** A stable-width duration field with at most three whole digits and two fractional digits. */
public class DurationEditText extends AppCompatEditText {
    public DurationEditText(Context context, AttributeSet attributes) {
        super(context, attributes);
        setFilters(new InputFilter[]{(source, start, end, destination, replaceStart, replaceEnd) -> {
            String value = destination.subSequence(0, replaceStart).toString()
                    + source.subSequence(start, end) + destination.subSequence(replaceEnd, destination.length());
            return value.matches("[0-9]{0,3}(?:\\.[0-9]{0,2})?") ? null : destination.subSequence(replaceStart, replaceEnd);
        }});
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.max(CompactUi.dp(getContext(), 80), (int) Math.ceil(getPaint().measureText("999.99"))
                + getCompoundPaddingLeft() + getCompoundPaddingRight() + CompactUi.dp(getContext(), 12));
        super.onMeasure(MeasureSpec.makeMeasureSpec(resolveSize(width, widthSpec), MeasureSpec.EXACTLY), heightSpec);
    }
}
