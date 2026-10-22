package heartguard.heartguard.main;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DomainSurfaceDeviceTest {
    // Test: Every theme uses a slightly lighter dark surface or darker light surface, including changed-app rows.
    // Why: Nested scrolling must be visible without a fixed color clashing with themes or double-applying row alpha.
    @Test public void surfacesRespectEveryThemeAndChangedAppTint() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int theme : new int[]{R.style.AppThemeTeal, R.style.AppThemeTealDark,
                    R.style.AppThemeBlue, R.style.AppThemeBlueDark,
                    R.style.AppThemePurple, R.style.AppThemePurpleDark,
                    R.style.AppThemeAmber, R.style.AppThemeAmberDark,
                    R.style.AppThemeOrange, R.style.AppThemeOrangeDark,
                    R.style.AppThemeGreen, R.style.AppThemeGreenDark}) {
                Context themed = new ContextThemeWrapper(context, theme);
                int background = CompactUi.color(themed, android.R.attr.colorBackground);
                boolean dark = ColorUtils.calculateLuminance(background) < 0.5;
                DomainListView domains = new DomainListView(themed, null);
                for (int parent : new int[]{background, dark ? 0x80444444 : 0x80cccccc}) {
                    domains.setSurfaceColor(parent);
                    int surface = ((ColorDrawable) domains.getBackground()).getColor();
                    int composite = ColorUtils.compositeColors(parent, background);
                    assertEquals(255, Color.alpha(surface));
                    assertNotEquals(composite, surface);
                    assertEquals(dark, ColorUtils.calculateLuminance(surface) > ColorUtils.calculateLuminance(composite));
                    assertTrue("Subtle surface separation", ColorUtils.calculateContrast(surface, composite) < 1.3);
                    int text = CompactUi.color(themed, android.R.attr.textColorPrimary);
                    assertTrue("Primary text remains readable", ColorUtils.calculateContrast(
                            ColorUtils.compositeColors(text, surface), surface) >= 4.5);
                }
            }
        });
    }
}
