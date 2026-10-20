# HeartGuard-MW

A modified GPLv3 build of [HeartGuard](https://github.com/hoodedpaladin/HeartGuard),
which derives from [NetGuard](https://github.com/M66B/NetGuard).
Modifications for HeartGuard-MW were made on October 20, 2026.

Version `2.317-mw.3` adds:

- **Global whitelist** in the main menu. Paste domains or HTTP/HTTPS URLs, one per
  line. Rules cover the host and its subdomains, across current and future apps.
  URL paths, ports and query strings are not separate firewall rules.
- **Block for this app** in the domain menu. This overrides global and app-specific
  allows. To remove an exception, delete its `block package:... host:...` rule
  from **Rules**. Existing rule delays still apply.
- **Check for updates** in the main menu. Downloads from the public MW GitHub
  release repository, verifies length, SHA-256, package identity, version and
  signing certificate, and opens Android's installer for confirmation.
- A non-debuggable release with the same application ID and signing key as
  the previous MW preview. NetGuard billing is disabled in this free non-Play build.

Global DNS blocks and lockdown remain in force. Domain matching depends on DNS
names observed by the VPN; a hostname is not recoverable from arbitrary encrypted
DNS traffic. This does not add URL-path filtering. Apps that are not routed through
the VPN cannot be filtered. Adding an app-specific block keeps fully allowed apps
inside the VPN so their domain exceptions can be checked.

The earlier search/scroll reset issue is not part of this release.

## Build

Use JDK 17, Android SDK platform 31, Build Tools 30.0.3, NDK 23.1.7779620,
and CMake 3.22.1. The wrapper uses Gradle 7.5.

Set `JAVA_HOME` and `ANDROID_HOME`. For signed builds, also set
`HEARTGUARD_KEYSTORE_PATH`, `HEARTGUARD_KEYSTORE_PASSWORD`, and
`HEARTGUARD_KEY_ALIAS`. Keep signing material outside Git. Without these, the
release APK is unsigned. To update an installed MW build, its original key is
required; a newly generated key cannot replace it.

Run `./Build-HeartGuard-MW.ps1` on Windows, or
`./gradlew testDebugUnitTest lintRelease assembleRelease` on other systems.
The output is in `app/build/outputs/apk/release/`.
Run `./gradlew connectedDebugAndroidTest` with a disposable emulator for device
tests. The instrumentation tests create and remove their own example-domain rules.
Run `./scripts/test-updater.ps1` with the same signing environment for upgrade
verification and Android installer tests. This installs an older-version fixture
on the disposable emulator. Rebuild normally afterward before distributing an APK.
Lint uses a baseline generated from the untouched upstream revision; new issues
are still checked.
Run `./scripts/test-release.ps1` to install and launch the actual minified release
on the disposable emulator. This checks for startup crashes that debug tests
cannot detect.

## Distribution

The development repository is private. Public releases are in
`phooBarred/heartguard-mw-downloads`. Each release includes the APK, its checksum,
and the corresponding GPLv3 source archive (including build scripts).
The private repository does not restrict recipients' GPL rights to that released
source. Original notices and [LICENSE](LICENSE) are retained.
