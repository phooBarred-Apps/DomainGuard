package heartguard.heartguard.main;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** Empty themed host for component instrumentation; excluded from release builds. */
public class ComponentTestActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
    }
}
