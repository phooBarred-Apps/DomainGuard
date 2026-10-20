# HeartGuard-MW

A modified GPLv3 build of [HeartGuard](https://github.com/hoodedpaladin/HeartGuard),
which derives from [NetGuard](https://github.com/M66B/NetGuard).
Modifications for HeartGuard-MW were made on October 20, 2026.

Version `2.317-mw.6` adds:

- **Search domains across apps** searches recorded attempts independently of app
  filters and shows each app/domain's latest recorded outcome. URLs are accepted.
- **Search global lists** filters either list and includes parent-domain rules
  that cover the searched host, with a "Covers this domain" label.
- **Global lists navigation** returns to the Global lists menu when you finish
  either list with Done or Android Back.
- **Show in toolbar if room** keeps Filter and Sort together instead of hiding
  Sort solely because the toolbar's action-count limit was reached.
- **Install update** starts the download, verifies the APK, and automatically
  opens Android's installation confirmation. No separate download action is
  needed inside HeartGuard-MW.

Version `2.317-mw.5` added:

- **Sort and filter buttons** in Settings > Options lets you choose always
  visible above the app list (the default), in the toolbar when there is room,
  or in the menu only. Always-visible controls remain available while searching.
- **Set as default** in Filter apps applies and saves the selected filters for
  future sessions. **Apply** uses them only for the current session. Search text
  is not saved as a default. **Reset filters** clears the current filters and
  search without overwriting the saved defaults.

Version `2.317-mw.4` added:

- **Allow for this app** on blocked-domain notifications. Each action refers to
  the domain and app shown, even when another connection arrives. Existing rule
  delays apply. Explicit blocks require review in the app; traffic attributed to
  a shared Android UID also requires review. Enable **Notify on internet access**
  in Settings to receive access notifications.
- **Global lists** in the main menu opens separate whitelist and blacklist
  dialogs. Paste domains or HTTP/HTTPS URLs, one per line. Rules cover the host
  and its subdomains, across current and future apps. The whitelist also accepts
  IP addresses. Red/green globe badges identify global rules and appear in the legend.
- **Stable live domain lists** retain row identity during updates and keep taps
  attached to the domain initially touched. App searches, domain searches,
  expanded apps and scroll position survive rule updates.
- **Sort and filter** controls provide one alphabetical app list by default,
  optional recent-activity/access/UID ordering, combined app filters, a result
  count and reset. Domain lists have search and sort controls. **Set as default**
  explicitly saves a chosen sort; temporary choices last for the current session.
- **Hide allowed domains** has a saved global default and one temporary override
  toggle in each expanded app.
- **Rule change delay** in Settings accepts seconds and displays the equivalent
  hours/minutes/seconds. It shows pending changes and uses the existing delay
  rules, including the delay before a reduction takes effect.
- **Android account backup** in Settings requests backup of preferences, rules
  (including pending rules), and the hosts file. Automatic requests after changes
  are optional and off by default. Android controls when requests run and how
  settings restore; this is not a Google Drive file or an exact backup schedule.
  Traffic history, DNS records and downloaded APKs are excluded.
- The raw rule editor's list has a scrollbar at the right edge when needed.

Also included from `2.317-mw.3`:

- **Allow globally** in a domain's menu adds it to the central whitelist.
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
tests, selecting the relevant test classes. The backup and installer fixtures
require the scripts below; the public update test requires GitHub connectivity.
The instrumentation tests create and remove their own example-domain rules.
Run `./scripts/test-backup.ps1` to exercise Android's backup and restore agent
using the emulator's local transport. This does not test a Google account upload.
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
