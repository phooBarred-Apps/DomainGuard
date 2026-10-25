"""Build and verify a merged DomainGuard release; upload only with --publish.

Uses Python's standard library, Git Credential Manager, JDK 17 and the Android SDK.
See docs/releases.md. This script never pushes source or overwrites a release.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = "phooBarred-Apps/DomainGuard"
DOWNLOADS = "phooBarred/DomainGuard-downloads"
PACKAGE = "heartguard.heartguard.main.mw"
CERTIFICATE = "145d3d426ae87e16407f02ef3f0a476c450d6bd8ed101fda97e31038c1fecd4b"
HISTORY = "app/src/main/res/raw/fork_update_history.json"
DEVICE_EVIDENCE = "Device tests compiled; emulator and physical-device tests were not run by this publisher."


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Authenticated API calls must stay at their explicitly selected endpoint."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, route, data=None, *, method=None, upload=None, optional=False):
        url = "https://api.github.com" + route
        headers = {"Authorization": "Bearer " + self.token,
                   "Accept": "application/vnd.github+json",
                   "User-Agent": "DomainGuard-release",
                   "X-GitHub-Api-Version": "2022-11-28"}
        if upload is not None:
            require(re.fullmatch(r"/repos/" + re.escape(DOWNLOADS) +
                                r"/releases/\d+/assets\?name=[A-Za-z0-9.-]+", route),
                    "Unexpected asset upload destination.")
            url = "https://uploads.github.com" + route
            data = upload.read_bytes()
            headers["Content-Type"] = {".apk": "application/vnd.android.package-archive",
                                       ".zip": "application/zip", ".txt": "text/plain"}.get(
                                           upload.suffix, "application/octet-stream")
        elif data is not None:
            data = json.dumps(data).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            with self.opener.open(urllib.request.Request(
                    url, data=data, headers=headers, method=method), timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if optional and error.code == 404:
                return None
            raise RuntimeError(f"GitHub {method or 'GET'} {route}: HTTP {error.code}") from None


def github_credentials(root):
    result = subprocess.run(
        ["git", "-c", "credential.interactive=never", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n", text=True, capture_output=True,
        cwd=root, timeout=25,
        env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never"))
    require(result.returncode == 0, "GitHub credentials unavailable in Git Credential Manager.")
    values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    require(values.get("password"), "GitHub credentials unavailable.")
    return GitHub(values["password"])


def require_merged_source(root, github):
    require(not git(root, "status", "--porcelain"), "Commit all source changes before releasing.")
    commit = git(root, "rev-parse", "HEAD")
    source = github.request(f"/repos/{SOURCE}")
    require(source["full_name"] == SOURCE and source["private"], "Unexpected source repository.")
    main = github.request(f"/repos/{SOURCE}/branches/main")
    require(main["commit"]["sha"] == commit, "Check out the merged main revision before releasing.")
    pulls = github.request(f"/repos/{SOURCE}/commits/{commit}/pulls?per_page=100")
    require(any(pr.get("merged_at") and pr["base"]["ref"] == "main" and
                pr["base"]["repo"]["id"] == source["id"] and
                pr.get("merge_commit_sha") == commit for pr in pulls),
            "Release source must have been merged through a pull request.")
    return commit


def version_parts(value):
    require(re.fullmatch(r"\d+(?:\.\d+)+", value), "Use a numeric DomainGuard version, such as 2.84.")
    parts = [int(part) for part in value.split(".")]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def require_new_release(github, version):
    downloads = github.request(f"/repos/{DOWNLOADS}")
    require(downloads["full_name"] == DOWNLOADS and not downloads["private"],
            "Unexpected downloads repository.")
    existing = github.request(f"/repos/{DOWNLOADS}/releases/tags/v{version}", optional=True)
    require(existing is None, "That release already exists. Inspect it; do not overwrite or rerun it.")
    latest = github.request(f"/repos/{DOWNLOADS}/releases/latest")
    require(version_parts(version) > version_parts(latest["tag_name"].removeprefix("v")),
            "The new version must be newer than the latest public release.")
    return downloads


def project_version(root, version):
    gradle = (root / "app/build.gradle").read_text(encoding="utf-8")
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, re.MULTILINE)
    code = re.search(r'^\s*versionCode\s*=\s*(\d+)\s*$', gradle, re.MULTILINE)
    require(name and code and name[1] == version, "Requested version does not match app/build.gradle.")
    history = json.loads((root / HISTORY).read_text(encoding="utf-8"))
    require(history and history[0]["tag_name"] == "v" + version,
            "Update history must start with the requested release.")
    return int(code[1])


def build_release(root, directory):
    required = ("JAVA_HOME", "ANDROID_HOME", "HEARTGUARD_KEYSTORE_PATH",
                "HEARTGUARD_KEYSTORE_PASSWORD", "HEARTGUARD_KEY_ALIAS")
    require(all(os.environ.get(name) for name in required),
            "Set JAVA_HOME, ANDROID_HOME and all three signing variables listed in docs/releases.md.")
    env = dict(os.environ)
    sockets = root / ".gradle/sockets"
    sockets.mkdir(parents=True, exist_ok=True)
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
                               f' -Djdk.net.unixdomain.tmpdir="{sockets}"').strip()
    wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
    tasks = [":app:clean", ":app:testDebugUnitTest", ":app:lintRelease",
             ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:assembleRelease"]
    print("Building and checking merged source. Build output: " + str(directory / "build.log"), flush=True)
    with (directory / "build.log").open("w", encoding="utf-8") as log:
        result = subprocess.run([str(root / wrapper), *tasks, "--rerun-tasks", "--no-daemon",
                                 "--console=plain"], cwd=root, env=env, stdout=log,
                                stderr=subprocess.STDOUT, timeout=3600)
    require(result.returncode == 0, "Build failed; inspect build.log. Nothing was uploaded.")


def build_tools_path(explicit):
    if explicit:
        return Path(explicit).resolve()
    sdk = os.environ.get("ANDROID_HOME")
    require(sdk, "Set ANDROID_HOME or pass --build-tools.")
    candidates = [p for p in (Path(sdk) / "build-tools").iterdir()
                  if re.fullmatch(r"\d+(?:\.\d+)+", p.name)]
    require(candidates, "No Android SDK build tools found.")
    return max(candidates, key=lambda p: version_parts(p.name))


def verify_artifacts(root, version, version_code, tools):
    reports = list((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    require(reports, "Missing unit test reports.")
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    require(totals["tests"] > totals["skipped"] and not totals["failures"] and not totals["errors"],
            "Unit tests did not pass.")
    lint = ET.parse(root / "app/build/reports/lint-results-release.xml").getroot()
    require(not any(issue.get("severity") in ("Error", "Fatal") for issue in lint.findall("issue")),
            "Release lint has errors.")
    apk = root / "app/build/outputs/apk/release/DomainGuard-release.apk"
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    aapt = tools / ("aapt.exe" if os.name == "nt" else "aapt")
    signature = subprocess.check_output([str(signer), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    require(re.search(r"Signer #1 certificate SHA-256 digest: " + CERTIFICATE, signature, re.IGNORECASE),
            "APK signing certificate changed.")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    package = f"name='{PACKAGE}' versionCode='{version_code}' versionName='{version}'"
    require(package in badging.splitlines()[0], "APK package or version differs from the requested release.")
    require("application-debuggable" not in badging, "Refusing to release a debuggable APK.")
    history = (root / HISTORY).read_bytes()
    with zipfile.ZipFile(apk) as archive:
        matches = [name for name in archive.namelist() if name.startswith("res/") and
                   name.endswith(".json") and archive.read(name) == history]
    require(len(matches) == 1, "APK update history differs from the source.")
    return dict(version=version, versionCode=version_code, certificate=CERTIFICATE,
                sha256=sha256(apk), unitTests=totals["tests"], skippedTests=totals["skipped"],
                failures=0, errors=0, lintErrors=0, deviceTests=DEVICE_EVIDENCE)


def prepare_assets(root, directory, commit, verification):
    apk = directory / "DomainGuard.apk"
    shutil.copyfile(root / "app/build/outputs/apk/release/DomainGuard-release.apk", apk)
    require(sha256(apk) == verification["sha256"], "APK changed after verification.")
    source = directory / "DomainGuard-source.zip"
    git(root, "archive", "--format=zip", "--prefix=DomainGuard/", "--output=" + str(source), commit)
    checksums = directory / "SHA256SUMS.txt"
    checksums.write_text("".join(f"{sha256(path)}  {path.name}\n" for path in (apk, source)), encoding="ascii")
    return [apk, source, checksums]


def verify_download(url, expected_hash):
    # Anonymous requests: never send GitHub credentials to a download/CDN URL.
    require(url.startswith(f"https://github.com/{DOWNLOADS}/releases/download/"),
            "Unexpected public download URL.")
    for attempt in range(6):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "DomainGuard-release-verification"})
            with urllib.request.urlopen(request, timeout=60) as response:
                received = hashlib.file_digest(response, "sha256").hexdigest()
            require(received == expected_hash, "Public download checksum does not match.")
            return
        except urllib.error.HTTPError as error:
            if error.code != 404 or attempt == 5:
                raise RuntimeError(f"Public download failed: HTTP {error.code}") from None
            time.sleep(5)


def publish_assets(github, version, notes, files, directory, downloads):
    release = github.request(f"/repos/{DOWNLOADS}/releases", {
        "tag_name": "v" + version, "target_commitish": downloads["default_branch"],
        "name": "DomainGuard " + version, "body": notes, "draft": True, "prerelease": False})
    save_json(directory / "draft.json", {"id": release["id"], "url": release["html_url"]})
    assets = []
    for path in files:
        digest = sha256(path)
        asset = github.request(f"/repos/{DOWNLOADS}/releases/{release['id']}/assets?name={path.name}",
                               upload=path, method="POST")
        require(asset["state"] == "uploaded" and asset["size"] == path.stat().st_size and
                asset.get("digest") == "sha256:" + digest, "Uploaded asset verification failed: " + path.name)
        assets.append(dict(name=path.name, url=asset["browser_download_url"], sha256=digest))
    published = github.request(f"/repos/{DOWNLOADS}/releases/{release['id']}",
                               {"draft": False, "make_latest": "true"}, method="PATCH")
    require(not published["draft"], "GitHub did not confirm publication.")
    latest = github.request(f"/repos/{DOWNLOADS}/releases/latest")
    require(latest["id"] == release["id"] and latest["tag_name"] == "v" + version,
            "GitHub did not confirm this release as latest.")
    for asset in assets:
        verify_download(asset["url"], asset["sha256"])
    result = dict(release=published["html_url"], assets=assets)
    save_json(directory / "publication.json", result)
    return result


def release(root, github, version, notes_path, tools, publish=False):
    version_parts(version)
    notes = notes_path.read_text(encoding="utf-8-sig").strip()
    require(notes, "Release notes are empty.")
    version_code = project_version(root, version)
    commit = require_merged_source(root, github)
    downloads = require_new_release(github, version)
    output = root / ".gradle/verification"
    output.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f"release-{version}-", dir=output))
    build_release(root, directory)
    verification = verify_artifacts(root, version, version_code, tools)
    require(require_merged_source(root, github) == commit, "Source changed while building.")
    verification["sourceCommit"] = commit
    save_json(directory / "verification.json", verification)
    files = prepare_assets(root, directory, commit, verification)
    notes += (f"\n\nValidation: signed release build, release lint, and {verification['unitTests']} unit tests"
              f" ({verification['skippedTests']} skipped). {DEVICE_EVIDENCE}\n\nSource commit: {commit}\n")
    (directory / "release-notes.md").write_text(notes, encoding="utf-8")
    if not publish:
        print("Verified locally; nothing uploaded. Artifacts: " + str(directory))
        return None
    # Recheck the release and source immediately before the first external write.
    downloads = require_new_release(github, version)
    require(require_merged_source(root, github) == commit, "Source changed before publication.")
    result = publish_assets(github, version, notes, files, directory, downloads)
    print("Published and verified: " + result["release"])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="Version already committed in app/build.gradle")
    parser.add_argument("--notes", type=Path, required=True, help="Reviewed release notes file")
    parser.add_argument("--build-tools", help="Android SDK build-tools directory; otherwise use the latest installed")
    parser.add_argument("--publish", action="store_true", help="Create and publish a new GitHub release after all checks")
    args = parser.parse_args()
    release(ROOT, github_credentials(ROOT), args.version, args.notes.resolve(),
            build_tools_path(args.build_tools), args.publish)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from None
