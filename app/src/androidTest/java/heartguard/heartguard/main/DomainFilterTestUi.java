package heartguard.heartguard.main;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import static org.junit.Assert.*;

/** Operates the real filter dialog, including native spinner popups. */
final class DomainFilterTestUi {
    static void choose(int allowed, int blocked) {
        select("domain_filter_allowed", R.array.domain_allowed_choices, allowed);
        select("domain_filter_blocked", R.array.domain_blocked_choices, blocked);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 apply = device.wait(Until.findObject(By.res("android", "button1")), 5000);
        assertNotNull(apply);
        apply.click();
        assertTrue(device.wait(Until.gone(By.res("android", "button1")), 5000));
    }

    static void select(String id, int labels, int selection) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 spinner = device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000);
        assertNotNull(spinner);
        spinner.click();
        UiObject2 popup = device.wait(Until.findObject(By.clazz("android.widget.ListView")), 5000);
        assertNotNull("Spinner popup must open before choosing its selected option", popup);
        UiObject2 choice = popup.findObject(By.text(context.getResources().getStringArray(labels)[selection]));
        assertNotNull(choice);
        choice.click();
        assertTrue(device.wait(Until.gone(By.clazz("android.widget.ListView")), 5000));
    }
}
