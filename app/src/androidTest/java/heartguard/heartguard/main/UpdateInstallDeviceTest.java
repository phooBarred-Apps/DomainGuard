package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.regex.Pattern;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Run with scripts/test-updater.ps1: the installed fixture has an older version and the same key. */
@RunWith(AndroidJUnit4.class)
public class UpdateInstallDeviceTest {
    private Context context;
    private File apk;
    private UpdateRelease release;
    private Activity activity;

    @Before public void setup() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("updateApkPath");
        assumeTrue("Run scripts/test-updater.ps1 for signed upgrade tests", path != null);
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File folder = new File(context.getCacheDir(), "updates");
        folder.mkdirs();
        apk = new File(folder, "test-release.apk");
        copy(new File(path), apk);
        release = describe(apk);
    }

    @After public void cleanup() {
        if (activity != null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        if (apk != null) apk.delete();
    }

    @Test public void acceptsNewerSignedReleaseAndProviderReadsIt() throws Exception {
        UpdateApk.verify(context, apk, release);
        android.net.Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", apk);
        try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertEquals('P', input.read());
            assertEquals('K', input.read());
        }
    }

    @Test public void rejectsTamperedFile() throws Exception {
        try (FileOutputStream output = new FileOutputStream(apk, true)) { output.write(0); }
        try { UpdateApk.verify(context, apk, release); fail("Tampered APK accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("incomplete")); }
    }

    @Test public void rejectsAnotherPackage() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        File other = new File(testContext.getApplicationInfo().sourceDir);
        try { UpdateApk.verify(context, other, describe(other)); fail("Wrong package accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("not an update")); }
    }

    @Test public void rejectsSameVersion() throws Exception {
        File installed = new File(context.getApplicationInfo().sourceDir);
        try { UpdateApk.verify(context, installed, describe(installed)); fail("Same version accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("newer version")); }
    }

    @Test public void opensAndroidInstallerAfterSourcePermission() throws Exception {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityUpdates.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(device.wait(Until.hasObject(By.res(context.getPackageName(), "update_action").enabled(true)), 25000));
        Method install = ActivityUpdates.class.getDeclaredMethod("install");
        install.setAccessible(true);
        Field apkField = ActivityUpdates.class.getDeclaredField("apk");
        Field releaseField = ActivityUpdates.class.getDeclaredField("release");
        apkField.setAccessible(true); releaseField.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                apkField.set(activity, apk);
                releaseField.set(activity, release);
                install.invoke(activity);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            UiObject2 button = device.wait(Until.findObject(By.res(context.getPackageName(), "update_action").enabled(true)), 5000);
            assertNotNull(button);
            button.click();
            UiObject2 permission = device.wait(Until.findObject(By.clazz("android.widget.Switch")), 10000);
            assertNotNull("Install-source permission switch", permission);
            if (!permission.isChecked()) permission.click();
            device.pressBack();
        }
        Pattern installer = Pattern.compile("com\\.(google\\.)?android\\.packageinstaller");
        assertTrue("Installer recognized DomainGuard", device.wait(Until.hasObject(
                By.pkg(installer).text("DomainGuard")), 15000));
        assertTrue("Android offers installation after staging", device.wait(Until.hasObject(
                By.pkg(installer).text(Pattern.compile("(?i)^(install|update)$")).enabled(true)), 15000));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "update-installer.png"));
        device.pressBack();
    }

    private UpdateRelease describe(File file) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        assertNotNull(info);
        java.security.MessageDigest hash = UpdateClient.sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return new UpdateRelease(info.versionName, "", URI.create("https://github.com"), file.length(), UpdateClient.hex(hash.digest()));
    }

    private void copy(File from, File to) throws IOException {
        try (FileInputStream input = new FileInputStream(from); FileOutputStream output = new FileOutputStream(to)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }
}
