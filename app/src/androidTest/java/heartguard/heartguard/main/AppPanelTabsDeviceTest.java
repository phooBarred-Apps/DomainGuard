package heartguard.heartguard.main;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.view.ViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppPanelTabsDeviceTest {
    // Test: Both tab labels and all three tools fit narrow/large-text layouts in both directions and all themes.
    // Why: The longer Info & controls label must not overlap tools, truncate text, or create a dead row on its tab.
    @Test public void tabLabelsAndToolsFitEveryThemeAndTextSize() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int theme : new int[]{R.style.AppThemeTeal, R.style.AppThemeTealDark,
                    R.style.AppThemeBlue, R.style.AppThemeBlueDark,
                    R.style.AppThemePurple, R.style.AppThemePurpleDark,
                    R.style.AppThemeAmber, R.style.AppThemeAmberDark,
                    R.style.AppThemeOrange, R.style.AppThemeOrangeDark,
                    R.style.AppThemeGreen, R.style.AppThemeGreenDark}) {
                for (float scale : new float[]{1, 1.5f, 2}) {
                    Configuration config = new Configuration(context.getResources().getConfiguration());
                    config.fontScale = scale;
                    Context themed = new ContextThemeWrapper(context.createConfigurationContext(config), theme);
                    for (int direction : new int[]{ViewCompat.LAYOUT_DIRECTION_LTR, ViewCompat.LAYOUT_DIRECTION_RTL}) {
                        View app = LayoutInflater.from(themed).inflate(R.layout.rule, null, false);
                        app.findViewById(R.id.domainSearchButton).setVisibility(View.VISIBLE);
                        AppPanelTabs tabs = app.findViewById(R.id.appPanelTabs);
                        ViewCompat.setLayoutDirection(tabs, direction);
                        int width = CompactUi.dp(themed, 288);
                        AppPanelTabs.select(app, false);
                        layout(tabs, width);
                        View tools = app.findViewById(R.id.domainControls);
                        TextView first = app.findViewById(R.id.appDomainsButton), second = app.findViewById(R.id.appOptionsButton);
                        assertTrue(first.getLeft() >= 0 && first.getRight() <= width);
                        assertTrue(second.getLeft() >= 0 && second.getRight() <= width);
                        assertTrue(tools.getLeft() >= 0 && tools.getRight() <= width);
                        assertTrue("Tools must be below tabs or beside them", tools.getTop() >= second.getBottom()
                                || (direction == ViewCompat.LAYOUT_DIRECTION_LTR ? tools.getLeft() >= second.getRight()
                                : tools.getRight() <= second.getLeft()));
                        assertEquals("Label fully rendered", second.length(), second.getLayout().getLineEnd(second.getLineCount() - 1));
                        int domainsHeight = tabs.getMeasuredHeight();
                        AppPanelTabs.select(app, true);
                        layout(tabs, width);
                        assertTrue(second.isSelected());
                        assertFalse(first.isSelected());
                        assertEquals(View.GONE, app.findViewById(R.id.llFilter).getVisibility());
                        assertTrue(tabs.getMeasuredHeight() <= domainsHeight);
                    }
                }
            }
        });
    }

    private static void layout(View tabs, int width) {
        tabs.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        tabs.layout(0, 0, width, tabs.getMeasuredHeight());
    }
}
