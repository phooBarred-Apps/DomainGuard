package heartguard.heartguard.main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;

final class UpdateClient {
    interface Progress { void changed(int percent); }
    private volatile boolean canceled;
    private volatile HttpURLConnection connection;

    UpdateRelease check(String installed) throws IOException {
        return UpdateRelease.parse(readListing(URI.create(BuildConfig.GITHUB_LATEST_API)), installed);
    }

    List<UpdateHistory.Entry> history() throws IOException {
        return UpdateHistory.load(page -> readListing(URI.create("https://api.github.com/repos/"
                + UpdateRelease.REPOSITORY + "/releases?per_page=" + UpdateHistory.PAGE_SIZE + "&page=" + page)));
    }

    private String readListing(URI uri) throws IOException {
        HttpURLConnection request = open(uri, false);
        try (InputStream input = request.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCanceled();
                if (output.size() + count > 2 * 1024 * 1024) throw new IOException("The update listing is too large.");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } finally { request.disconnect(); connection = null; }
    }

    File download(UpdateRelease release, File directory, Progress progress) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot save the update. Check free storage.");
        File temporary = File.createTempFile("download-", ".part", directory);
        File verified = new File(directory, release.sha256 + ".apk");
        try {
            HttpURLConnection request = open(release.url, true);
            try (InputStream input = request.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                copyVerified(input, output, release, progress);
                output.getFD().sync();
            } finally { request.disconnect(); connection = null; }
            checkCanceled();
            if (verified.exists() && !verified.delete()) throw new IOException("Cannot replace the saved update.");
            if (!temporary.renameTo(verified)) throw new IOException("Cannot save the verified update.");
            return verified;
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    void copyVerified(InputStream input, OutputStream output, UpdateRelease release, Progress progress) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[16384];
        long total = 0;
        int count, lastPercent = -1;
        while ((count = input.read(buffer)) != -1) {
            checkCanceled();
            total += count;
            if (total > release.size || total > UpdateRelease.MAX_APK_BYTES)
                throw new IOException("The APK is larger than expected.");
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
            int percent = (int) (total * 100 / release.size);
            if (percent != lastPercent) { progress.changed(percent); lastPercent = percent; }
        }
        if (total != release.size || !hex(digest.digest()).equals(release.sha256))
            throw new IOException("The APK is incomplete or its checksum does not match. Download it again.");
    }

    private HttpURLConnection open(URI uri, boolean download) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkCanceled();
            if (!allowed(uri, download)) throw new IOException("The update request redirected outside GitHub.");
            HttpURLConnection request = (HttpURLConnection) uri.toURL().openConnection();
            connection = request;
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(15000);
            request.setReadTimeout(15000);
            request.setUseCaches(false);
            request.setRequestProperty("User-Agent", "DomainGuard-Updater");
            request.setRequestProperty("Accept", download ? "application/octet-stream" : "application/vnd.github+json");
            int status;
            try { status = request.getResponseCode(); }
            catch (IOException failure) { request.disconnect(); throw failure; }
            if (status == 200) return request;
            String location = request.getHeaderField("Location");
            request.disconnect();
            if (Arrays.asList(301, 302, 303, 307, 308).contains(status) && location != null) {
                try { uri = uri.resolve(location); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid update redirect.", invalid); }
                continue;
            }
            if (status == 404) throw new IOException("No public DomainGuard release is available yet.");
            if (status == 403 || status == 429) throw new IOException("GitHub is limiting update checks. Try again later.");
            throw new IOException("GitHub could not provide the update (HTTP " + status + ").");
        }
        throw new IOException("Too many update redirects.");
    }

    static boolean allowed(URI uri, boolean download) {
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1 &&
                uri.getFragment() == null && (download
                ? Arrays.asList("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(uri.getHost())
                : "api.github.com".equals(uri.getHost()));
    }

    void cancel() {
        canceled = true;
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    private void checkCanceled() throws IOException {
        if (canceled) throw new IOException("Update request canceled.");
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
