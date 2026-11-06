package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Color;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ActionColorDeviceTest {
    // Test: Actions and the red extended-delay warning meet 4.5:1 contrast in every light/dark app theme.
    // Why: Action meanings and the longer wait caused by an app delay must stay recognizable and readable.
    @Test public void actionColorsRemainReadableInEveryTheme() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int theme : new int[]{R.style.AppThemeTeal, R.style.AppThemeTealDark,
                    R.style.AppThemeBlue, R.style.AppThemeBlueDark,
                    R.style.AppThemePurple, R.style.AppThemePurpleDark,
                    R.style.AppThemeAmber, R.style.AppThemeAmberDark,
                    R.style.AppThemeOrange, R.style.AppThemeOrangeDark,
                    R.style.AppThemeGreen, R.style.AppThemeGreenDark}) {
                Context themed = new AlertDialog.Builder(new ContextThemeWrapper(context, theme)).getContext();
                int background = CompactUi.color(themed, android.R.attr.colorBackground);
                Button button = new androidx.appcompat.widget.AppCompatButton(themed);
                CompactUi.actionButton(button);
                int positive = button.getCurrentTextColor();
                assertTrue(Color.green(positive) > Color.red(positive));
                assertContrast(theme, positive, background);
                CompactUi.destructiveButton(button);
                int destructive = button.getCurrentTextColor();
                assertTrue(Color.red(destructive) > Color.green(destructive));
                assertContrast(theme, destructive, background);
                DelayNotice notice = new DelayNotice(themed);
                notice.setReason("Wait extended to 6 hours by a longer app delay.");
                int warning = ((android.widget.TextView) notice.findViewById(R.id.delay_notice_reason)).getCurrentTextColor();
                assertTrue("Longer-wait warning is red", Color.red(warning) > Color.green(warning));
                assertContrast(theme, warning, DelayNotice.surface(themed));
                CompactUi.dismissButton(button);
                int neutral = button.getCurrentTextColor();
                assertEquals(Color.red(neutral), Color.green(neutral));
                assertEquals(Color.red(neutral), Color.blue(neutral));
                assertContrast(theme, neutral, background);
            }
        });
    }

    private static void assertContrast(int theme, int foreground, int background) {
        double contrast = ColorUtils.calculateContrast(ColorUtils.compositeColors(foreground, background), background);
        assertTrue("Theme " + theme + " text contrast " + contrast, contrast >= 4.5);
    }
}
