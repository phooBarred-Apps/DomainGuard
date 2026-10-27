package heartguard.heartguard.main;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.regex.Pattern;

final class NotificationShade {
    private NotificationShade() { }

    static UiObject2 action(UiDevice device, String label) {
        // Android's footer also says "Clear all"; only notification action controls belong here.
        BySelector target = By.res("android", "action0").text(Pattern.compile(
                Pattern.quote(label), Pattern.CASE_INSENSITIVE));
        for (int attempt = 0; attempt < 6; attempt++) {
            UiObject2 action = device.wait(Until.findObject(target), 500);
            if (action != null) return action;
            for (UiObject2 expand : device.findObjects(By.res("android", "expand_button"))) {
                try {
                    if (!"Expand".equalsIgnoreCase(expand.getContentDescription())) continue;
                    expand.click();
                    action = device.wait(Until.findObject(target), 500);
                    if (action != null) return action;
                } catch (StaleObjectException changed) { break; }
            }
            // Expanded groups can be taller than the shade. Reach lower children as a user would.
            UiObject2 notifications = device.findObject(By.res("com.android.systemui", "notification_stack_scroller"));
            if (notifications != null) notifications.scroll(Direction.DOWN, .65f);
        }
        return null;
    }
}
