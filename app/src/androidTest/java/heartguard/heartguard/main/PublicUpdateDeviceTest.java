package heartguard.heartguard.main;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in check against the published release, using the older-version emulator fixture. */
@RunWith(AndroidJUnit4.class)
public class PublicUpdateDeviceTest {
    @Test public void publicReleaseDownloadsAndVerifiesOnAndroid() throws Exception {
        assumeTrue("Requires a published release", "true".equals(
                InstrumentationRegistry.getArguments().getString("runLiveUpdate")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UpdateClient client = new UpdateClient();
        File downloaded = null;
        try {
            UpdateRelease release = client.check(BuildConfig.VERSION_NAME);
            assertNotNull("A newer MW release is published", release);
            downloaded = client.download(release, new File(context.getCacheDir(), "updates"), percent -> {});
            UpdateApk.verify(context, downloaded, release);
            assertEquals(release.size, downloaded.length());
            assertNull("The same release is not offered again", client.check(release.version));
        } finally {
            client.cancel();
            if (downloaded != null) downloaded.delete();
        }
    }
}
