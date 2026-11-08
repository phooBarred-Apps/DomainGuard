# Publishing DomainGuard releases

Use `scripts/publish-release.py` for every new APK release. Update this script
when the release process changes; do not copy it into a version-specific script.
It never pushes source commits, merges pull requests, or replaces an existing
release or draft.

## Prepare the source

1. Obtain authorization to publish an APK. A source or documentation merge alone
   does not authorize an APK release.
2. Update `versionName` and increase `versionCode` in `app/build.gradle`. Put the
   new release first in `app/src/main/res/raw/fork_update_history.json`, retaining
   earlier entries. Write and review the release notes.
3. Follow the pull request workflow in [Contributing](../README-DomainGuard.md#contributing).
   After merging, check out the exact current revision of the source repository's
   `main` branch with a clean working tree.

The publisher checks GitHub directly: local HEAD must equal current `main` and
must be the merge commit of a merged PR targeting that repository's `main`.
It checks again after building and immediately before uploading.

## Environment

Use Python 3.11 or newer (standard library only), Git, Git Credential Manager,
JDK 17 and the Android build dependencies in [Build](../README-DomainGuard.md#build).
The signed-in GitHub account needs permission to publish releases in the public
`phooBarred-Apps/DomainGuard` repository, which holds both source and APKs.
Credentials are read from Git Credential Manager without an interactive prompt
or printing the token.

Set these environment variables in the release shell:

| Variable | Value |
| --- | --- |
| `JAVA_HOME` | JDK 17 directory |
| `ANDROID_HOME` | Android SDK directory |
| `HEARTGUARD_KEYSTORE_PATH` | Existing release keystore path |
| `HEARTGUARD_KEYSTORE_PASSWORD` | Existing keystore password |
| `HEARTGUARD_KEY_ALIAS` | Existing signing alias |

Keep signing material and passwords outside Git. These existing environment
variable names are also used by Gradle. The publisher verifies the installed
app's original package ID and signing certificate; a new key cannot upgrade it.

## Verify, then publish

Run from the repository root. These examples use **2.84 as an example only**;
substitute the version already merged into `main` and your reviewed notes file.
An already published version cannot be used for a new release.

```powershell
python -B scripts/publish-release.py --version 2.84 --notes .gradle/verification/release-notes.md --serial emulator-5590
```

Without `--publish`, the script reads GitHub state, builds and prepares the
files locally. It does not create a release or upload anything. Each preparation
cleans the app's build output and runs unit tests, release lint, the debug build,
device-test compilation and the signed release build. Do not run another build
or edit the checkout concurrently.

Verification checks the test reports, lint errors, APK signature, package ID,
version name/code, non-debuggable status and packaged update history. Test counts
come from the actual reports. Preparation then requires a running, dedicated
Android 12 (API 31) disposable emulator selected by `--serial`. It installs the
matching debug and test APKs, runs the existing native VPN/DNS tests with
host-local synthetic TCP/UDP/DNS fixtures, and runs the Android attribution,
database, and rule-action tests selected in `scripts/release_runtime.py`.
Finally it installs and launches the exact signed release APK saved in the bundle,
checks the foreground activity and watches for crashes. Test failures, skips,
assumption failures, missing methods/results, disconnected emulators, nonzero
commands and timeouts all prevent publication. There is no skip flag.

Use an isolated emulator with no personal data, DomainGuard initially stopped,
no active pauses, no configured rule delay and Manage system apps off, such as
`DomainGuard_Attribution_Test`. The native tests
temporarily grant VPN permission within that emulator and restore their rules;
the runner stops the app afterward. Host loopback port 53 must be available for
the synthetic DNS fixture. The runner neither launches an emulator nor saves
snapshots. A missing prerequisite is a failed gate, not a skipped pass.

Native tests check the shell client's actual route into Android's VPN and finish
service teardown before the next test. Rule tests keep routing inside the VPN
throughout the change; a separate transition test checks automatic capture of a
previously excluded app after the VPN is replaced. Blocking assertions require a
fresh blocked access record for the fixture's UID and protocol, so a network
timeout alone cannot pass a blocking check. A startup regression holds Android's
main queue to verify that an enacted rule is not mistaken for a ready VPN.
The route probe connects a UDP socket without sending a payload; it does not
retry a blocked request until a test happens to pass.
Automatic update checks are disabled only for the fixture and restored afterward.
Failures retain bounded service/native diagnostics in the runtime log; tests are
not automatically retried. These checks do not promise uninterrupted protection
while Android replaces a VPN interface.

The gate covers Android framework/SQLite/JNI/native VPN behavior on the debug
build plus release packaging/startup on the signed APK. It is not full functional
testing of minified code, older Android versions, ARM or physical devices.
Add feature-specific runtime checks when changing affected boundaries, including
installer, backup, notification presentation and lifecycle behavior. This app has
no deployed Node/Workers backend; tests for those runtimes would not apply here.
Staged global allows are checked through native TCP/UDP, shared expiry while
cleanup is held, Android database/restore logic, legacy rules and both dialog
themes. When changing the backup payload, also run `scripts/test-backup.ps1`
for a fresh-install Android local-transport round trip; this does not verify
Google's cloud service.

When publication is authorized, pass the output directory printed by preparation:

```powershell
python -B scripts/publish-release.py --publish --bundle .gradle/verification/release-2.84-EXAMPLE
```

This does **not** run Gradle or read a later APK from `app/build`. It checks the
bundle's file hashes and original successful verification, inspects the saved
APK's signature/package/version/history, and confirms the source is still merged
`main`. The source revision includes tracked build configuration; a separate
fingerprint detects changes to local Gradle settings, build environment and JDK
metadata without storing their contents. Changed inputs require fresh preparation.
`runtime.json` binds the passed runtime policy, exact required test identities,
device/API/ABI, APK hashes and logs to that source/configuration/release APK.
Publication checks the evidence and log hashes and reparses completed test results
before its first GitHub write. Legacy bundles without this evidence are rejected.

Preparation records the final notes as part of the bundle. Review
`release-notes.md` there before publication. If notes or artifacts need changing,
prepare again; do not edit the verified bundle. Signing passwords are not stored
in the manifest. Keep the signing environment configured between steps.

After these checks it creates a draft release and uploads the saved files:

- `DomainGuard.apk`
- `DomainGuard-source.zip`, made from the exact source commit using `git archive`
- `SHA256SUMS.txt`, covering both files

The release tag points to the exact verified source commit. Each release includes
an explicit source link as well as the corresponding source archive.

Only after GitHub confirms every uploaded file's size and SHA-256 does the
publisher make the release public and latest. It then downloads all three files
anonymously and verifies their checksums. It rejects versions older than or
equal to the current latest release.

By default, APK inspection uses the latest installed stable SDK Build Tools.
Pass `--build-tools <directory>` to choose an installed version explicitly.

## Outputs and failures

Each run prints a unique output directory beneath `.gradle/verification/`.
It retains `build.log`, `verification.json`, the three release files and the
final `release-notes.md`, plus `runtime.json` and the three `runtime-*.log` files.
Publication also records `draft.json` as soon as GitHub
creates the draft and `publication.json` after public download verification.

If anything fails, inspect the error, output directory and GitHub release before
retrying. An upload failure normally leaves a draft. A failure after publication
can leave a public release even without `publication.json`. Do not assume the
absence of a success message means nothing happened. Existing drafts/releases
stop subsequent runs; investigate and resolve the specific failure rather than
blindly rerunning or overwriting assets.

For an already authorized one-step release, `--version ... --notes ... --serial ... --publish`
still prepares once and publishes that same bundle. Use `--bundle` after a
separate preparation to avoid a second build. Historical verification reports
without the bundle schema and hashes cannot be reused.

## Maintaining the publisher

```powershell
python -B -m unittest discover -s scripts -p test_publish_release.py -v
python -B -m unittest discover -s scripts -p test_runtime_guard.py -v
```

These regression tests use temporary Git repositories and simulated GitHub and
Android build responses. They exercise source/PR checks, artifact validation,
release assembly and publication failure paths without uploading or building an
APK. They do not substitute for the publisher's real release-time checks.

## Publication routes and enforcement limits

- `publish-release.py`, both one-step and saved-bundle modes, is the maintained
  APK publication route. Both require the runtime evidence above before any
  draft, upload or latest-release update. Failed preparation cannot emit a
  publishable manifest.
- `Build-DomainGuard.ps1` and Gradle produce local builds only; they do not upload.
  Building an APK directly is not release approval or a substitute for the gate.
- `publish-preview.py` accepts reviewed PDFs only and preserves existing release
  assets/metadata. It does not publish APKs. Archived publishers are inactive.
- No GitHub Actions deployment workflow exists in this repository. GitHub's web
  UI, direct release API/CLI calls and an administrator modifying the scripts or
  local evidence can bypass a local check. Existing credentials and repository
  privileges are unchanged. This is enforced by the maintained publisher, not
  an unbypassable server rule or a cryptographic attestation service.

The integrity checks follow the relevant [OWASP supply-chain guidance](https://cheatsheetseries.owasp.org/cheatsheets/Software_Supply_Chain_Security_Cheat_Sheet.html).
Android tests use [AndroidJUnitRunner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)
on the actual emulator runtime; desktop mocks verify only the release guard's
failure handling and do not establish Android compatibility.

The twelve former local APK publishers were archived under
`.gradle/verification/archive/apk-publishers-20261025/` with original paths and
SHA-256 hashes in `manifest.json`. Their files have an `.archived` suffix and
are retained only as historical references. [Preview tools](previews.md) and
`scripts/test-release.ps1` (the emulator release smoke test) serve separate
purposes and have their own maintained entry points.

Completed local migration/edit scripts, version-specific verifiers and old build
wrappers are separately archived under
`.gradle/verification/archive/maintenance-20261025/`, with original paths and
hashes in its manifest. The maintained build entry point is
`Build-DomainGuard.ps1`; device checks remain in `scripts/test-*.ps1` and
`scripts/test-pause-network.py`. Local environment setup, when present, lives in
the ignored `.gradle/verification/local-build-env.ps1`. It contains private
machine/signing settings and must never be committed or published.
