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
The signed-in GitHub account needs access to the private source repository and
permission to publish in the public downloads repository. Credentials are read
from Git Credential Manager without an interactive prompt or printing the token.

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
python -B scripts/publish-release.py --version 2.84 --notes .gradle/verification/release-notes.md
```

Without `--publish`, the script reads GitHub state, builds and prepares the
files locally. It does not create a release or upload anything. Each preparation
cleans the app's build output and runs unit tests, release lint, the debug build,
device-test compilation and the signed release build. Do not run another build
or edit the checkout concurrently.

Verification checks the test reports, lint errors, APK signature, package ID,
version name/code, non-debuggable status and packaged update history. Test counts
come from the actual reports. The publisher compiles device tests but does not
run an emulator or a physical device; release notes state that limitation.
Perform any separately requested device checks before authorizing publication.

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

Preparation records the final notes as part of the bundle. Review
`release-notes.md` there before publication. If notes or artifacts need changing,
prepare again; do not edit the verified bundle. Signing passwords are not stored
in the manifest. Keep the signing environment configured between steps.

After these checks it creates a draft release and uploads the saved files:

- `DomainGuard.apk`
- `DomainGuard-source.zip`, made from the exact source commit using `git archive`
- `SHA256SUMS.txt`, covering both files

Only after GitHub confirms every uploaded file's size and SHA-256 does the
publisher make the release public and latest. It then downloads all three files
anonymously and verifies their checksums. It rejects versions older than or
equal to the current latest release.

By default, APK inspection uses the latest installed stable SDK Build Tools.
Pass `--build-tools <directory>` to choose an installed version explicitly.

## Outputs and failures

Each run prints a unique output directory beneath `.gradle/verification/`.
It retains `build.log`, `verification.json`, the three release files and the
final `release-notes.md`. Publication also records `draft.json` as soon as GitHub
creates the draft and `publication.json` after public download verification.

If anything fails, inspect the error, output directory and GitHub release before
retrying. An upload failure normally leaves a draft. A failure after publication
can leave a public release even without `publication.json`. Do not assume the
absence of a success message means nothing happened. Existing drafts/releases
stop subsequent runs; investigate and resolve the specific failure rather than
blindly rerunning or overwriting assets.

For an already authorized one-step release, `--version ... --notes ... --publish`
still prepares once and publishes that same bundle. Use `--bundle` after a
separate preparation to avoid a second build. Historical verification reports
without the bundle schema and hashes cannot be reused.

## Maintaining the publisher

```powershell
python -B -m unittest discover -s scripts -p test_publish_release.py -v
```

These regression tests use temporary Git repositories and simulated GitHub and
Android build responses. They exercise source/PR checks, artifact validation,
release assembly and publication failure paths without uploading or building an
APK. They do not substitute for the publisher's real release-time checks.

The twelve former local APK publishers were archived under
`.gradle/verification/archive/apk-publishers-20261025/` with original paths and
SHA-256 hashes in `manifest.json`. Their files have an `.archived` suffix and
are retained only as historical references. Preview-upload tools and
`scripts/test-release.ps1` (the emulator release smoke test) serve separate
purposes and remain in place.

Completed local migration/edit scripts, version-specific verifiers and old build
wrappers are separately archived under
`.gradle/verification/archive/maintenance-20261025/`, with original paths and
hashes in its manifest. The maintained build entry point is
`Build-DomainGuard.ps1`; device checks remain in `scripts/test-*.ps1` and
`scripts/test-pause-network.py`. Local environment setup, when present, lives in
the ignored `.gradle/verification/local-build-env.ps1`. It contains private
machine/signing settings and must never be committed or published.
