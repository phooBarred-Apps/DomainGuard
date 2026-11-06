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
import xml.etree.ElementTree as ET
import zipfile

from github_assets import REPOSITORY, github_credentials, require, sha256, verify_download
from release_runtime import run_checks, verify_evidence


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "heartguard.heartguard.main.mw"
CERTIFICATE = "145d3d426ae87e16407f02ef3f0a476c450d6bd8ed101fda97e31038c1fecd4b"
HISTORY = "app/src/main/res/raw/fork_update_history.json"
DEVICE_EVIDENCE = ("Mandatory Android 12 emulator checks passed: native VPN/DNS and Android rule/database "
                   "tests on the matching debug build, plus launch/crash checks on this signed release APK. "
                   "Other Android versions, ARM devices and physical-device behavior are not covered by this gate.")
ASSET_NAMES = ("DomainGuard.apk", "DomainGuard-source.zip", "SHA256SUMS.txt")


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def require_merged_source(root, github):
    require(not git(root, "status", "--porcelain"), "Commit all source changes before releasing.")
    commit = git(root, "rev-parse", "HEAD")
    source = github.request(f"/repos/{REPOSITORY}")
    require(source["full_name"] == REPOSITORY and not source["private"], "Unexpected source repository.")
    main = github.request(f"/repos/{REPOSITORY}/branches/main")
    require(main["commit"]["sha"] == commit, "Check out the merged main revision before releasing.")
    pulls = github.request(f"/repos/{REPOSITORY}/commits/{commit}/pulls?per_page=100")
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
    downloads = github.request(f"/repos/{REPOSITORY}")
    require(downloads["full_name"] == REPOSITORY and not downloads["private"],
            "Unexpected downloads repository.")
    existing = github.request(f"/repos/{REPOSITORY}/releases/tags/v{version}", optional=True)
    require(existing is None, "That release already exists. Inspect it; do not overwrite or rerun it.")
    tag = github.request(f"/repos/{REPOSITORY}/git/ref/tags/v{version}", optional=True)
    require(tag is None, "That tag already exists. Inspect its source before preparing a new release.")
    latest = github.request(f"/repos/{REPOSITORY}/releases/latest", optional=True)
    require(latest is None or version_parts(version) > version_parts(latest["tag_name"].removeprefix("v")),
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


def local_build_inputs(root):
    """Fingerprint untracked Gradle inputs without recording their contents or secrets."""
    names = ("JAVA_HOME", "ANDROID_HOME", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT",
             "GRADLE_USER_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS",
             "HEARTGUARD_KEY_ALIAS")
    environment = {name: value for name, value in os.environ.items()
                   if name in names or name.startswith("ORG_GRADLE_PROJECT_")}
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    paths = [root / "local.properties", gradle_home / "gradle.properties",
             gradle_home / "init.gradle", gradle_home / "init.gradle.kts"]
    if os.environ.get("JAVA_HOME"):
        paths.append(Path(os.environ["JAVA_HOME"]) / "release")
    paths.extend(sorted((gradle_home / "init.d").glob("*.gradle*")))
    values = [environment, [(str(path.resolve()), sha256(path) if path.is_file() else None)
                            for path in paths]]
    return hashlib.sha256(json.dumps(values, sort_keys=True).encode("utf-8")).hexdigest()


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
    verify_apk(root, apk, version, version_code, tools)
    return dict(version=version, versionCode=version_code, certificate=CERTIFICATE,
                sha256=sha256(apk), unitTests=totals["tests"], skippedTests=totals["skipped"],
                failures=0, errors=0, lintErrors=0, deviceTests=DEVICE_EVIDENCE)


def verify_apk(root, apk, version, version_code, tools):
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


def prepare_assets(root, directory, commit, verification):
    apk = directory / "DomainGuard.apk"
    shutil.copyfile(root / "app/build/outputs/apk/release/DomainGuard-release.apk", apk)
    require(sha256(apk) == verification["sha256"], "APK changed after verification.")
    source = directory / "DomainGuard-source.zip"
    git(root, "archive", "--format=zip", "--prefix=DomainGuard/", "--output=" + str(source), commit)
    checksums = directory / "SHA256SUMS.txt"
    checksums.write_text("".join(f"{sha256(path)}  {path.name}\n" for path in (apk, source)), encoding="ascii")
    return [apk, source, checksums]


def publish_assets(github, version, notes, files, directory, commit, hashes):
    release = github.request(f"/repos/{REPOSITORY}/releases", {
        "tag_name": "v" + version, "target_commitish": commit,
        "name": "DomainGuard " + version, "body": notes, "draft": True, "prerelease": False})
    save_json(directory / "draft.json", {"id": release["id"], "url": release["html_url"]})
    assets = []
    for path in files:
        digest = hashes[path.name]
        require(sha256(path) == digest, "Bundle file changed before upload: " + path.name)
        asset = github.request(f"/repos/{REPOSITORY}/releases/{release['id']}/assets?name={path.name}",
                               upload=path, method="POST")
        require(asset["state"] == "uploaded" and asset["size"] == path.stat().st_size and
                asset.get("digest") == "sha256:" + digest, "Uploaded asset verification failed: " + path.name)
        assets.append(dict(name=path.name, url=asset["browser_download_url"], sha256=digest))
    published = github.request(f"/repos/{REPOSITORY}/releases/{release['id']}",
                               {"draft": False, "make_latest": "true"}, method="PATCH")
    require(not published["draft"], "GitHub did not confirm publication.")
    latest = github.request(f"/repos/{REPOSITORY}/releases/latest")
    require(latest["id"] == release["id"] and latest["tag_name"] == "v" + version,
            "GitHub did not confirm this release as latest.")
    # Draft upload URLs can contain an untagged placeholder. Verify the final public URLs.
    public_assets = {asset["name"]: asset for asset in latest["assets"]}
    for asset in assets:
        public = public_assets.get(asset["name"])
        require(public is not None and public.get("digest") == "sha256:" + asset["sha256"],
                "Published asset verification failed: " + asset["name"])
        asset["url"] = public["browser_download_url"]
        verify_download(asset["url"], asset["sha256"])
    result = dict(release=published["html_url"], assets=assets)
    save_json(directory / "publication.json", result)
    return result


def release(root, github, version, notes_path, tools, publish=False, serial=None):
    require(re.fullmatch(r"emulator-\d+", serial or ""),
            "Select a disposable emulator with --serial before preparing a release.")
    version_parts(version)
    notes = notes_path.read_text(encoding="utf-8-sig").strip()
    require(notes, "Release notes are empty.")
    version_code = project_version(root, version)
    commit = require_merged_source(root, github)
    require_new_release(github, version)
    inputs = local_build_inputs(root)
    output = root / ".gradle/verification"
    output.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f"release-{version}-", dir=output))
    build_release(root, directory)
    verification = verify_artifacts(root, version, version_code, tools)
    require(require_merged_source(root, github) == commit, "Source changed while building.")
    require(local_build_inputs(root) == inputs, "Local build configuration changed while building.")
    files = prepare_assets(root, directory, commit, verification)
    run_checks(root, directory, directory / "DomainGuard.apk", commit, inputs, serial)
    verify_evidence(directory, commit, inputs, verification["sha256"])
    require(require_merged_source(root, github) == commit, "Source changed during runtime checks.")
    require(local_build_inputs(root) == inputs, "Local build configuration changed during runtime checks.")
    notes += (f"\n\nValidation: signed release build, release lint, and {verification['unitTests']} unit tests"
              f" ({verification['skippedTests']} skipped). {DEVICE_EVIDENCE}\n\n"
              f"[Corresponding source code](https://github.com/{REPOSITORY}/tree/{commit}) "
              "is also included in DomainGuard-source.zip.\n")
    (directory / "release-notes.md").write_text(notes, encoding="utf-8")
    verification.update(schemaVersion=2, sourceCommit=commit, localBuildInputs=inputs,
                        files={path.name: sha256(path) for path in files +
                               [directory / "release-notes.md", directory / "runtime.json"]})
    save_json(directory / "verification.json", verification)
    if not publish:
        print("Verified locally; nothing uploaded. Publish this bundle without rebuilding:\n"
              'python -B scripts/publish-release.py --publish --bundle "' + str(directory) + '"')
        return directory
    return publish_bundle(root, github, directory, tools)


def publish_bundle(root, github, directory, tools):
    verification = json.loads((directory / "verification.json").read_text(encoding="utf-8"))
    require(verification.get("schemaVersion") == 2, "Prepare a new bundle with mandatory runtime checks.")
    version = verification["version"]
    version_parts(version)
    require(project_version(root, version) == verification["versionCode"], "Bundle version code changed.")
    require(require_merged_source(root, github) == verification["sourceCommit"], "Bundle source changed; prepare again.")
    require(local_build_inputs(root) == verification["localBuildInputs"],
            "Local build configuration changed; prepare a new bundle.")
    require(verification["unitTests"] > verification["skippedTests"] and
            not any(verification[key] for key in ("failures", "errors", "lintErrors")),
            "Bundle does not contain successful verification.")
    files = [directory / name for name in ASSET_NAMES]
    for path in files + [directory / "release-notes.md", directory / "runtime.json"]:
        require(sha256(path) == verification["files"][path.name], "Bundle file changed: " + path.name)
    require(verification["files"]["DomainGuard.apk"] == verification["sha256"], "Bundle APK hash is inconsistent.")
    verify_apk(root, directory / "DomainGuard.apk", version, verification["versionCode"], tools)
    verify_evidence(directory, verification["sourceCommit"], verification["localBuildInputs"], verification["sha256"])
    notes = (directory / "release-notes.md").read_text(encoding="utf-8")
    # Recheck GitHub immediately before the first external write. Never rebuild or use app/build here.
    require_new_release(github, version)
    require(require_merged_source(root, github) == verification["sourceCommit"], "Source changed before publication.")
    result = publish_assets(github, version, notes, files, directory, verification["sourceCommit"], verification["files"])
    print("Published and verified: " + result["release"])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", help="Version already committed in app/build.gradle")
    parser.add_argument("--notes", type=Path, help="Reviewed release notes file")
    parser.add_argument("--bundle", type=Path, help="Publish an existing verified output directory without rebuilding")
    parser.add_argument("--build-tools", help="Android SDK build-tools directory; otherwise use the latest installed")
    parser.add_argument("--publish", action="store_true", help="Create and publish a new GitHub release after all checks")
    parser.add_argument("--serial", help="Dedicated running Android 12 emulator for mandatory runtime checks")
    args = parser.parse_args()
    if args.bundle:
        if not args.publish or args.version or args.notes:
            parser.error("Use --bundle with --publish, without --version or --notes.")
        publish_bundle(ROOT, github_credentials(ROOT), args.bundle.resolve(), build_tools_path(args.build_tools))
    else:
        if not args.version or not args.notes or not args.serial:
            parser.error("Preparation requires --version, --notes and --serial.")
        release(ROOT, github_credentials(ROOT), args.version, args.notes.resolve(),
                build_tools_path(args.build_tools), args.publish, args.serial)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from None
