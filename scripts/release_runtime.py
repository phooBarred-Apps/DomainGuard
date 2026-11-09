"""Mandatory Android release checks; only a dedicated, disposable emulator is accepted."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from github_assets import require, sha256

PACKAGE = "heartguard.heartguard.main.mw"
PREFIX = "heartguard.heartguard.main."
POLICY = 1
NETWORK_TESTS = tuple(PREFIX + "AppPauseNetworkDeviceTest#" + name for name in (
    "nativeBlockedDnsIsVisible", "nativeConnectionsStopAtPauseExpiry",
    "nativeConnectionsStopAtTemporaryAllowExpiry", "nativeConnectionsStopAtGlobalPauseExpiry", "nativeStagedGlobalAllowUsesSharedExpiry", "nativeStagedGlobalPauseUsesSharedExpiry",
    "nativeHostnameEvidence", "nativeStartupWaitsForVpn", "nativeQueuedStopsKeepNewerStartsAlive", "nativeRoutingTransitionEnforcesBlock"))
JAVA_TESTS = tuple(PREFIX + cls + "#" + name for cls, names in (
    ("GlobalPauseDeviceTest", ("globalAllowDialogExplainsIndependentWaits", "longerAppDelayExplainsGlobalWait", "longerAppDelayWarnsBeforeSaving", "overridesAndGlobalActionsRespectEffectiveDelays", "unknownDelaySettingExplainsAndProtectsChanges")),
    ("StagedGlobalAllowDeviceTest", ("globalActivationKeepsLongerAppWaiting", "sharedExpiryAndAppSpecificFullDuration",
        "restoreAndLegacyRulesKeepTheirTiming", "globalOffStagesAndCancellationRestoresProtection",
        "globalPauseUsesOneWindowAndNoLateActivation", "globalBlockRemovalUsesDefaultThenAppWaits", "ruleFamilyTimingAudit",
        "unknownDelayPolicyAppliesToExistingGlobalChanges", "strictUnknownTurnOffWaitsAndSurvivesRestore")),
    ("DomainAttributionDeviceTest", ("enrichWithoutChangingPolicyAndSearchObservedName",
        "clearedHistoryStaysDeleted", "newerFlowWins", "dnsOwnershipAndActualTtl", "migrationPreservesHistory")),
    ("DomainActionsDeviceTest", ("globalBlockPreservesAllHistoryAndStillBlocksDomains",
        "allowAndBlockMenusDefaultToAppAndSaveSelectedScope")),
    ("AccessNotificationDeviceTest", ("globalParentAllowRespectsAppExceptions",
        "globalAndAddressBlocksKeepAlertsUntilResolved", "explicitBlocksAddressExistingAlertsAcrossTheirScope",
        "globalBlockNotificationsAreOptIn")),
    ("RecentTrafficDeviceTest", ("unknownDnsResetReviewsGlobalBlockAndHonorsDelay",
        "resetPreservesOtherActionsAndPolicyIdentity", "resetMatchesGlobalAndLocalScopesWithoutUnrelatedRules"))
) for name in names)
LOGS = ("runtime-native.log", "runtime-java.log", "runtime-release.log")


def instrumentation_passes(output, expected=None):
    """ADB can exit zero for failed, skipped, crashed or empty test runs."""
    status = {}
    passed = []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1])
            require(code in (0, 1), "Android runtime test failed or was skipped.")
            if code == 0:
                require("class" in status and "test" in status, "Missing runtime test identity.")
                passed.append(status["class"] + "#" + status["test"])
            status = {}
    summary = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    require(len(summary) == 1 and int(summary[0]) == len(passed) > 0
            and len(set(passed)) == len(passed), "Incomplete Android runtime test results.")
    require(not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_ABORTED", output),
            "Android instrumentation did not complete successfully.")
    if expected is not None:
        require(set(passed) == set(expected), "Required Android runtime tests are missing.")
    return sorted(passed)


def run(command, root, timeout, log=None):
    try:
        result = subprocess.run(command, cwd=root, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired as error:
        if log:
            log.write_text("Runtime check timed out.\n", encoding="utf-8")
        raise RuntimeError("Runtime check timed out; publication is blocked.") from error
    output = result.stdout + "\n" + result.stderr
    if log:
        log.write_text(output, encoding="utf-8")
    require(result.returncode == 0, "Runtime command failed; inspect " + str(log or command[0]))
    return output


def run_checks(root, directory, apk, commit, inputs, serial):
    require(re.fullmatch(r"emulator-\d+", serial or ""), "Select a disposable emulator with --serial.")
    require(os.environ.get("ANDROID_HOME"), "ANDROID_HOME is required for runtime checks.")
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    target = [adb, "-s", serial]
    require(run(target + ["shell", "getprop", "ro.kernel.qemu"], root, 15).strip() == "1",
            "Runtime checks require a running disposable emulator.")
    require(run(target + ["shell", "getprop", "sys.boot_completed"], root, 15).strip() == "1",
            "Emulator has not finished booting.")
    sdk = run(target + ["shell", "getprop", "ro.build.version.sdk"], root, 15).strip()
    require(sdk == "31", "Required runtime baseline is Android 12 / API 31.")
    device = {"serial": serial, "sdk": sdk,
              "fingerprint": run(target + ["shell", "getprop", "ro.build.fingerprint"], root, 15).strip(),
              "abi": run(target + ["shell", "getprop", "ro.product.cpu.abi"], root, 15).strip()}
    debug = root / "app/build/outputs/apk/debug/DomainGuard-debug.apk"
    tests = root / "app/build/outputs/apk/androidTest/debug/DomainGuard-debug-androidTest.apk"
    artifacts = {"release": sha256(apk), "debug": sha256(debug), "tests": sha256(tests)}
    # Each directory is unique; no prior pass is carried into a failed rerun.
    evidence = directory / "runtime.json"
    require(not evidence.exists(), "Runtime evidence already exists; use a new verification directory.")
    try:
        for path in (debug, tests):
            run(target + ["install", "-r", str(path)], root, 90)
        print("Running mandatory native VPN/DNS checks.", flush=True)
        native = run([sys.executable, "-B", str(root / "scripts/test-pause-network.py"),
                      "--adb", adb, "--serial", serial, "--dns"], root, 360, directory / LOGS[0])
        instrumentation_passes(native, NETWORK_TESTS)
        print("Running mandatory Android database and rule-action checks.", flush=True)
        java = run(target + ["shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(JAVA_TESTS),
                            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], root, 240, directory / LOGS[1])
        instrumentation_passes(java, JAVA_TESTS)
        print("Checking the exact signed release APK on Android.", flush=True)
        shell = "pwsh.exe" if os.name == "nt" else "pwsh"
        smoke = run([shell, "-NoProfile", "-File", str(root / "scripts/test-release.ps1"),
                     "-Serial", serial, "-Apk", str(apk)], root, 90, directory / LOGS[2])
        require("Minified release launched and remained active without a crash." in smoke,
                "Release runtime check did not report success.")
        require(artifacts == {"release": sha256(apk), "debug": sha256(debug), "tests": sha256(tests)},
                "APK inputs changed during runtime checks.")
        result = dict(policy=POLICY, sourceCommit=commit, localBuildInputs=inputs, artifacts=artifacts,
                      device=device, native=sorted(NETWORK_TESTS), android=sorted(JAVA_TESTS), releaseSmoke=True,
                      logs={name: sha256(directory / name) for name in LOGS})
        evidence.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        return result
    finally:
        # Never leave synthetic VPN enforcement running after a timeout or failure.
        subprocess.run(target + ["shell", "am", "force-stop", PACKAGE], capture_output=True, timeout=15)


def verify_evidence(directory, commit, inputs, apk_hash):
    result = json.loads((directory / "runtime.json").read_text(encoding="utf-8"))
    require(result.get("policy") == POLICY and result.get("sourceCommit") == commit
            and result.get("localBuildInputs") == inputs
            and result.get("artifacts", {}).get("release") == apk_hash,
            "Runtime evidence does not match this source/configuration/APK.")
    require(result.get("native") == sorted(NETWORK_TESTS) and result.get("android") == sorted(JAVA_TESTS)
            and result.get("releaseSmoke") is True and result.get("device", {}).get("sdk") == "31",
            "Mandatory runtime checks have not passed.")
    require(set(result.get("logs", {})) == set(LOGS), "Missing runtime logs.")
    for name in LOGS:
        require(sha256(directory / name) == result["logs"][name], "Runtime log changed: " + name)
    instrumentation_passes((directory / LOGS[0]).read_text(encoding="utf-8"), NETWORK_TESTS)
    instrumentation_passes((directory / LOGS[1]).read_text(encoding="utf-8"), JAVA_TESTS)
    require("Minified release launched and remained active without a crash." in
            (directory / LOGS[2]).read_text(encoding="utf-8"), "Missing release runtime success.")
