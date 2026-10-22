package heartguard.heartguard.main;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DurationInputDeviceTest {
    // Test: All four layouts round automatically, keep exact stored values safe, and restrict input.
    // Why: Rule delays, global/app pauses and temporary rules must queue the same duration their UI describes.
    @Test public void sharedControlsRoundAndRestrictInput() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.content.Context context = instrumentation.getTargetContext();
        ActivityMain activity = (ActivityMain) instrumentation.startActivitySync(new Intent(context, ActivityMain.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                int[][] layouts = {{R.layout.rule_delay, R.id.rule_delay_input, R.id.rule_delay_unit},
                        {R.layout.global_pause, R.id.global_pause_amount, R.id.global_pause_unit},
                        {R.layout.app_pause, R.id.app_pause_amount, R.id.app_pause_unit},
                        {R.layout.temporary_rule, R.id.temporary_rule_amount, R.id.temporary_rule_unit}};
                for (int[] layout : layouts) {
                    View root = activity.getLayoutInflater().inflate(layout[0], null);
                    EditText amount = root.findViewById(layout[1]);
                    Spinner unit = root.findViewById(layout[2]);
                    DurationInput binding = new DurationInput(amount, unit, 90, () -> {});
                    assertEquals("90", amount.getText().toString());
                    amount.setText("99.49");
                    assertEquals(99, binding.seconds());
                    amount.setText("99.5");
                    assertEquals(100, binding.seconds());
                    amount.setText(".49");
                    assertEquals(0, binding.seconds());
                    amount.setText("999.99");
                    assertEquals("999.99", amount.getText().toString());
                    amount.getText().append("9");
                    assertEquals("999.99", amount.getText().toString());
                    amount.getText().insert(0, "1");
                    assertEquals("999.99", amount.getText().toString());
                    amount.getText().delete(0, amount.length());
                    assertEquals("", amount.getText().toString());
                    binding.setSeconds(356868);
                    assertEquals("99.13", amount.getText().toString());
                    assertEquals(RuleDelay.Unit.HOURS, unit.getSelectedItem());
                    assertEquals(356868, binding.seconds());
                    binding.setSeconds(86401);
                    assertEquals("", amount.getText().toString());
                    try { binding.seconds(); fail("An unrepresentable existing duration must not silently change"); }
                    catch (IllegalArgumentException expected) { }
                }
                assertEquals("30 seconds", RuleDelay.duration(activity, 30));
                assertEquals("1 minute and 30 seconds", RuleDelay.duration(activity, 90));
                assertEquals("4 days, 3 hours, 7 minutes, and 48 seconds", RuleDelay.duration(activity, 356868));
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
}
