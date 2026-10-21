# DomainGuard

A modified GPLv3 build of [HeartGuard](https://github.com/hoodedpaladin/HeartGuard),
which derives from [NetGuard](https://github.com/M66B/NetGuard).
DomainGuard continues the HeartGuard-MW fork. Latest changes: October 21, 2026.

Version `2.4` (DomainGuard) adds:

- **DomainGuard branding** with the same Android package and signing certificate, so existing settings remain in place. Earlier HeartGuard-MW versions and their original release notes remain in Update history.
- **Compact toolbar and expanded apps:** Search, Filter and Sort stay in the top toolbar; app count and Reset appear only when filtering. Expanded apps put domain tools together, with app details under Options. Buttons are frameless.
- **Consistent action colors:** Add, Agree, Save, Apply and Resume use green; Delete, Remove, Cancel pending and Pause use red; plain Cancel and Done use gray. Shades adapt to light and dark themes.
- **Main-screen domain search:** hold Search to choose apps or domains. Domain mode uses the normal app layout, expands matching apps, and copies every distinct matching domain. Switching modes retains separate queries. Restoring a query does not summon the keyboard.
- **Central global lists:** an icon-labelled Blacklist/Whitelist chooser, search and add controls, separate bulk-add dialogs, consistent pending timers, and Back/Done navigation to the chooser.
- **Allow/Block dialogs:** a plain globe toggles Apply globally off/on. The parent-domain dropdown is connected to the input and lists every parent down to the registrable domain, using an offline Public Suffix List. Delay notices reflect the selected action and scope.
- **Display settings:** consolidated appearance and domain-list controls, the approved Hide allowed icon, and exact timestamps with day-of-month and seconds or optional elapsed time. Per-app domain search starts hidden; tap the magnifying glass to open it, or enable Always show domain search per app.
- **Pause DomainGuard for an app:** choose seconds, minutes, hours or days, up to seven days. The normal rule delay applies before the pause begins. While paused, all of that app's traffic is allowed, including global and app-specific blocks; existing rules are retained and restored automatically afterward. Pending pauses show their duration and start time, with Cancel pending pause directly below. Active pauses offer a green Resume now button below the end time. Android apps sharing a network UID pause together, with a notice in the dialog. The red pause icon is explained in the Legend.
- **Filter and sort defaults:** Set as default stays separate from confirmation buttons; Filter has Reset on the right, and applicable sort directions share the selected criterion's row.
- **Rule delay:** units and readable duration, with help in the top-right corner.
- **Updates:** one Install update action and a compact history control. History retains all published MW releases and DomainGuard releases, including full versions and dates.
- **Device backup:** Automatic backup toggle, phone backup settings link, and an information dialog. Android controls timing and restore; there is no manual backup-request button.
- **Legend and help:** grouped icon descriptions, a persistent scrollbar when needed, and Help & tutorials in the menu.
- **Notify on global blocks:** off by default. Enable it to include global-rule blocks in access notifications; other block notifications continue normally.
- **Raw rule editor:** literal search with previous/next matches and a draggable right-edge scrollbar. Navigation preserves the entire document; there are no top/bottom shortcut buttons.
- **Traffic history:** changing global blocks retains existing access logs.

Install 2.4 using its direct APK link once: the old MW updater recognizes only MW version tags. Future DomainGuard releases are supported by the new updater.

Version `2.317-mw.7` adds:

- **Rule delay units** lets you enter seconds, minutes, hours or days, with
  singular/plural labels and an exact conversion to the existing delay rules.
- **Copy domains** copies all matching domains from the cross-app search once
  each, one per line, including results outside the visible area.
- **Backup explanation** wraps in full instead of truncating the third paragraph.
- **Hide global block notifications** is an optional setting under Settings >
  Options. It hides access notifications for active global blacklist matches,
  including subdomains, while retaining blocking and access history.
- **Per-app domain controls** put Sort and the optional search button beside Hide
  allowed domains. Settings > Options > Always show domain search per app keeps
  the field visible by default. Turn it off to reveal the field with the search
  icon; tapping again clears and closes that app's search for the current session.

Version `2.317-mw.6` added:

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

Run `./Build-DomainGuard.ps1` on Windows, or
`./gradlew testDebugUnitTest lintRelease assembleRelease` on other systems.
The output is in `app/build/outputs/apk/release/`.
Run `./gradlew connectedDebugAndroidTest` with a disposable emulator for device
tests, selecting the relevant test classes. The backup and installer fixtures
require the scripts below; the public update test requires GitHub connectivity.
The instrumentation tests create and remove their own example-domain rules.
After installing the debug app and test APK, run `python scripts/test-pause-network.py`
to verify real TCP and UDP connections stop at pause expiry, using host-local echo servers.
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

The bundled Public Suffix List is distributed under MPL 2.0; see [its license](LICENSE-public-suffix-list.txt) and `app/src/main/res/raw/public_suffix_notice.txt`.
