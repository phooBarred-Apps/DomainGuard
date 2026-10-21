package heartguard.heartguard.main;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

final class UpdateApk {
    static void verify(Context context, File file, UpdateRelease release) throws IOException {
        if (file.length() != release.size) throw new IOException("The update file is incomplete.");
        MessageDigest digest = UpdateClient.sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!UpdateClient.hex(digest.digest()).equals(release.sha256))
            throw new IOException("The saved APK checksum has changed. Download it again.");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        try {
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            if (candidate == null || !installed.packageName.equals(candidate.packageName))
                throw new IOException("This APK is not an update for DomainGuard.");
            if (!release.version.equals(candidate.versionName) || code(candidate) <= code(installed))
                throw new IOException("This APK is not the advertised newer version.");
            if (Build.VERSION.SDK_INT >= 24 && candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
                throw new IOException("This update requires a newer Android version.");
            Set<String> expected = signatures(installed), actual = signatures(candidate);
            if (expected.isEmpty() || !expected.equals(actual))
                throw new IOException("The update was signed by a different key.");
            // Android's installer independently verifies APK integrity and the signing key.
        } catch (PackageManager.NameNotFoundException missing) {
            throw new IOException("Cannot verify the installed app.", missing);
        }
    }

    private static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static Set<String> signatures(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
                : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null)
            for (Signature signature : signatures)
                result.add(UpdateClient.hex(UpdateClient.sha256().digest(signature.toByteArray())));
        return result;
    }
}
