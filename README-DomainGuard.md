# DomainGuard

A modified GPLv3 build of [HeartGuard](https://github.com/hoodedpaladin/HeartGuard),
which derives from [NetGuard](https://github.com/M66B/NetGuard).
Latest changes: October 26, 2026.

Version `2.85`: **Recent traffic** now combines the same access records shown under individual apps, without requiring detailed recording. Repeated attempts update their existing record. The usual **Hide allowed domains** button uses the global display default and can be toggled for the session. Rule icons and domain colors match the per-app lists.

Version `2.84` adds **Menu > Recent traffic**: individual recorded attempts across apps, including repeats, with a rolling three-minute window, sorting, and eye buttons to hide or restore apps. Tap an attempt for the existing Allow/Block actions. The view explains how to enable detailed recording when needed. Domain searching and filtering also avoid repeated database reads.

Version `2.83` updates in-app update handling and cleans up release documentation.

Version `2.82` fixes:

- **Parent-domain choices for shared hosting:** the dropdown now offers githubusercontent.com for release-assets.githubusercontent.com, and equivalent parent choices on other shared-hosting domains. Registry endings such as com and co.uk remain excluded.

Version `2.81` adds:

- **Restore saved filters:** the circular arrow clears the search and restores saved filter defaults. A saved User apps choice stays selected; sorting is preserved.
- **Copy the filtered domains:** cross-app Copy uses both the main search and each app's own domain search, plus Hide allowed and the displayed row limit. Duplicate domains are copied once. Each app also has a Copy domains icon beside its domain tools.
- **Clear empty results:** a cross-app domain search with no results says "No matching domains logged in any apps."
- **Compact-list switch in settings:** enable or disable Compact domain lists directly in Display settings. Tap the setting's name to choose its height or open the full-screen example. Saving a height does not change the switch.

Version `2.8` adds:

- **Raw Rules filters:** tap the toolbar title to select All rules or Global rules. Each header has a separate caret opening Block, Allow and Other toggles, all on initially. Choices apply immediately and stay separate for each scope during the session. Temporary rules and pending removals follow their underlying rule type. The plain globe marks Global other rules.
- **Find and scroll raw rules:** the Rules list now has the raw editor's Find, previous/next match controls and draggable scrollbar. Find searches displayed rules without hiding them or revealing partner secrets. Copy all and export continue to include every rule.
- **Compact duration inputs:** rule delays, global/app pauses and temporary domain rules share a fixed-width decimal field and an adjacent unit picker. Enter up to three digits before the decimal and two after it; values round automatically to the nearest second, with halves rounded up. Readable durations run from days down to seconds. Existing rule delays and the seven-day limit still apply.
- **Smaller icon feedback:** icon buttons retain their touch targets, with 36dp press feedback and a borderless 32dp selected state. Per-app search and Hide allowed show when they are on. Domain rows have balanced left/right padding.
- **Independent domain searches:** an app matching the main domain search stays visible when its own search has no results, with a message naming the local search.

These filters only change the display. They do not edit rules, affect protection, or change delays.

Version `2.7` adds:

- **Pause all apps:** turning the main switch off offers Resume automatically. Enable it and choose seconds, minutes, hours or days, up to seven days; leave it off for an indefinite turn-off. The applicable rule delay is shown before confirmation.
- **Compact pause status:** a subtle countdown strip appears only while a global pause is pending or active. Cancel and Resume open Yes/Cancel confirmations with the exact start/end time, including month, day and seconds.
- **Visible delay notices:** the turn-off/pause and allow-app dialogs use the same expandable timing strip. Current rules remain active during the wait.
- **App delay overrides:** Info & controls > Rule delay can inherit the general delay or use a longer or shorter value. Increases are immediate; reductions wait the difference, including a return to a shorter general delay. A change from six hours to four hours waits two hours.
- **Protected bypasses:** global pauses, global allows and turning protection off honor the longest applicable app delay. Apps sharing a network UID use the stronger delay when paused together. Existing rules survive a pause, and existing TCP/UDP sessions are checked again at expiry. A permanent turn-off clears the old global pause before the next manual enable.

Global pause raw-rule example: `pause seconds:300`. An app override uses `delay package:com.android.chrome 14400`; removing that override returns to the general delay after any required reduction wait.

See [Changes since HeartGuard](CHANGES-SINCE-HEARTGUARD.md) for the organized list of changes that remain in the current app.

Version `2.6` adds:

- **Domains / Info & controls tabs:** expanded apps show one panel at a time. App information and controls are immediately reachable without scrolling through the domain list. Tabs remain frameless, with the active tab underlined; domain tools wrap when narrow screens or larger text need more room.
- **Default panel setting:** enable Display settings > App list > Open Info & controls by default to start there. Each app can override the default during the current session. Domain searches open Domains so matches stay visible.
- **Easier scrolling:** the domain list has a narrow strip on each side where swiping scrolls the whole app screen. Swiping within a compact domain list still scrolls its domains.
- **Distinct domain surface:** domain lists are subtly lighter in dark themes and darker in light themes, retaining the surrounding app's theme tint.

Version `2.5` adds:

- **Temporary Allow and Block:** enable Temporary in either domain dialog, choose seconds, minutes, hours or days (up to seven days), and optionally apply it globally. The toggle and global scope start off. Allows honor the normal rule delay; blocks begin immediately. The duration starts on activation, and expiry removes only that temporary rule. Existing block priorities and permanent rules remain in force. Global lists show the remaining duration.
- **Compact domain lists:** Display settings lets you cap each expanded list at 2–10 rows, with an inner scrollbar for longer lists. Tap the example app to open a full-screen Preview with a Back arrow; previewing does not save changes or add traffic data.
- **Resume control:** an actively paused app shows a green Play/Resume button in place of the red Pause button.
- **Clearer controls:** Live updates pauses only the displayed traffic updates for all apps. Logging and filtering continue unchanged. The access-history trash icon sits beside the app protection Pause and gear controls.
- **Help and Legend:** Help & tutorials now opens the existing Legend in a separate dialog. Back or Done returns to Help. The Legend explains Resume and live-update controls.

Temporary raw-rule examples:

```text
temporary seconds:300 allow package:com.android.chrome host:example.com
temporary seconds:3600 block package:com.android.chrome host:example.com
temporary seconds:300 allow host:example.com
temporary seconds:3600 blockdomain example.com
```

Saved activation times survive restarts and backup/restore; timers do not restart. Automatic expiry is enforced in packet decisions, cached IP decisions and existing VPN sessions, independently of delayed Android alarms. Removing a temporary block early uses the normal removal delay. The existing Rules screen can remove an app-specific temporary rule; global entries can also be removed through Global lists.

Version `2.4` (DomainGuard) adds:

- **DomainGuard branding** replaces the previous app name for new releases.
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
  needed inside the app.

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

## Contributing

Changes to `main` go through pull requests in
[`phooBarred-Apps/DomainGuard`](https://github.com/phooBarred-Apps/DomainGuard).
Use a descriptive working branch and one pull request per related feature or fix.
The local `mw` remote points to this repository; `origin` is the upstream
HeartGuard project and is not the destination for DomainGuard changes.

When a push is authorized, push the working branch, open a pull request against
`main`, review the final diff, and run the checks appropriate to the change.
Wait for applicable automated checks, then squash-merge the pull request using
its verified current head. Report the pull request link and the checks performed.
Stop for failures, conflicts, unexpected changes, or an explicit review/testing
hold. A push authorization includes the merge after these checks pass.

This is the project's working convention. GitHub's free plan does not enforce
required reviews or provide native auto-merge for this private repository.
Merging through the authorized account preserves the pull request record without
claiming an independent reviewer approved the change.

Release publication is a separate action. Publish only when a release is
authorized and its source has been merged. Release scripts must not push source
commits directly to `main`.

## Distribution

Use the single publisher, `scripts/publish-release.py`, for new APK releases.
See [Publishing releases](docs/releases.md) for preparation, verification and
publication. It builds from merged source and uploads only with `--publish`.

The development repository is private. Public releases are in
[`phooBarred/DomainGuard-downloads`](https://github.com/phooBarred/DomainGuard-downloads). Each release includes the APK, its checksum,
and the corresponding GPLv3 source archive (including build scripts).
The private repository does not restrict recipients' GPL rights to that released
source. Original notices and [LICENSE](LICENSE) are retained.

The bundled Public Suffix List is distributed under MPL 2.0; see [its license](LICENSE-public-suffix-list.txt) and `app/src/main/res/raw/public_suffix_notice.txt`.
