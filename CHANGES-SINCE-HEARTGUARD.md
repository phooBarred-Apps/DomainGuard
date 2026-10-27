# Changes since HeartGuard

Current in DomainGuard 2.86. This combines the work since the fork, groups related refinements, and leaves out replaced designs and features inherited unchanged from HeartGuard.

## 1. Allowing and blocking internet access

### Creating rules

- **Simpler Allow and Block dialogs** — Tap a domain, choose Allow or Block, and use the globe to apply the rule to all apps instead of just the selected app.
- **Parent-domain picker** — The dropdown attached to the domain field lists usable parents, including shared-hosting domains such as githubusercontent.com, while excluding registry endings such as com and co.uk.
- **Pasting web addresses** — Domain dialogs and global lists extract the host from an HTTP/HTTPS URL and check its format before saving. IP-address input also has stronger checks where supported; the raw editor still uses rule syntax.

### Rules for every app

- **Central global lists** — Menu > Global lists opens separate whitelist and blacklist dialogs with matching icons, search, bulk entry, pending changes and consistent Back/Done navigation.
- **App-specific block exceptions** — A domain can stay blocked for one app even when a global or whole-app allow would otherwise permit it.
- **Global-rule indicators** — Green and red globe badges distinguish global allows and blocks from app-specific decisions, with explanations in the Legend.

### Temporary rules and delays

- **Temporary Allow and Block** — Either rule can expire after a chosen number of seconds, minutes, hours or days, up to seven days, without deleting the permanent rules underneath it.
- **Rule-delay settings** — A regular settings dialog replaces memorizing raw delay syntax, with unit selection, readable durations, pending-change cancellation and help.
- **Compact time entry** — Delays, pauses and temporary rules use a narrow decimal field with an adjacent unit picker, round automatically to whole seconds, and describe durations from days down to seconds.
- **Per-app delay overrides** — Each app can inherit the general delay or use a longer or shorter one, with a reduction waiting the difference before taking effect.
- **Visible delay notices** — Rule dialogs show the relevant wait, and the allow-app and global turn-off/pause dialogs share an expandable explanation strip.

## 2. Pausing protection

### One app or all apps

- **Pause an app** — Info & controls can allow all traffic for that app temporarily, including traffic normally blocked by domain rules, and automatically restore its rules afterward.
- **Pause all of DomainGuard** — Turning the main switch off offers Resume automatically, with a duration of up to seven days, or an indefinite turn-off when that option is off.
- **Delay protection for pauses** — Pauses wait before starting, global bypasses honor the longest applicable app delay, and apps sharing one Android network identity honor the stronger delay.

### Status and recovery

- **App pause controls** — Pending app pauses show their duration and start time, while active pauses show an end time and a green Play/Resume control.
- **Compact global pause strip** — A subtle countdown strip appears while a global pause is pending or active, with short Cancel or Resume actions and Yes/Cancel confirmation dialogs.
- **Reliable expiry** — Timed rules and pauses retain their deadlines across restarts and restore protection for existing connections as well as new ones when they expire.

## 3. Finding, filtering and sorting

### Searching

- **Two main search modes** — Holding the main magnifying glass switches between searching app names/package names and searching recorded domains across apps, one mode at a time.
- **Domain results in the app list** — Cross-app domain searches keep the normal layout, display matching apps expanded, and retain separate queries when switching modes. Empty results explain that no matching domains were logged in any apps.
- **Search inside an app** — Each app has its own domain search, hidden behind a magnifying glass by default, with a setting to keep it visible. No local matches leaves a clear message without removing an app that matches the main search.
- **Copy filtered domains** — Copy works across matching apps or from one app's own button. It respects both searches, Hide allowed and each list's displayed row limit, and copies each domain once, including rows you can scroll to.

### Filters and ordering

- **Combined app filters** — Filter by allowed/blocked defaults, recorded activity and user/system apps, with options for disabled apps and apps without internet permission.
- **Raw-rule filters** — All rules and Global rules each offer Block, Allow and Other toggles; the list changes immediately without changing the underlying rules.
- **Useful sort choices** — Apps and domains can use relevant name, activity and access ordering, with direction controls and app-ID sorting where applicable.
- **Saved defaults** — Sort and Filter have separate Set as default actions, while ordinary changes remain limited to the current session.
- **Focused count and reset** — The app count and reset control appear only when a search or filter is active. Reset clears the search and restores saved filter defaults while keeping the chosen sort.

## 4. App layout and everyday use

### Recent traffic

- **Focused app filters** — Filter by the app's default access, user/system app type and enabled status. Recent traffic has its own saved defaults; Reset restores them without changing the main list or other traffic controls.

- **Hide allowed domains** — The usual icon applies the same filter as the per-app lists. It starts from the global display default, can be changed for the session, and leaves recording, rules and saved history unchanged.
- **Combined live timeline** — Menu > Recent traffic combines the existing per-app access records, newest first. Repeated attempts update their existing record. The rolling window starts at three minutes and can be extended up to three days; other sorts are available.
- **Hide noisy apps** — Each row has the app icon and an eye button. Hiding filters all traffic for that network identity from this view only; Hidden apps restores it. Hidden apps, time window and sort are session choices that survive screen recreation.
- **Review and act** — Domain colors and rule icons match the per-app lists, including global rules and pending allows. Domain actions reuse the existing Allow/Block dialogs and delays. Newest-first follows updated records at the top while preserving position when reviewing older entries; Load more reveals additional records.
- **No extra recording setup** — The combined view reuses the access history already collected for the main app lists. It does not require or change the separate detailed-logging rule or switch. Only VPN-observed access appears.

### Expanded apps

- **Domains and Info & controls tabs** — App information and controls are directly accessible without scrolling through the entire domain list, with an option to open that panel by default.
- **Compact domain lists** — A switch in Display settings enables a height limit of two to ten rows, with a scrollbar for longer lists. Tap the setting's name to choose the height or open a full-screen sample preview with a Back arrow; saving height does not change the switch.
- **Separate scrolling areas** — Narrow side strips scroll the whole app screen, while a subtle theme-aware background identifies the inner domain list.
- **Hide allowed domains** — A global display preference supplies the default, and each expanded app can override it for the current session with one icon.

### Appearance and interaction

- **Toolbar preferences** — Search, Filter and Sort use icons, with a setting for whether Sort and Filter always appear in the toolbar, appear when space permits, or stay in the menu.
- **Organized display settings** — Appearance, app-list and domain-list preferences are collected together instead of being scattered among unrelated options.
- **Timestamp choices** — Domain entries can show exact times with day-of-month and seconds, or time elapsed.
- **Consistent controls** — Updated controls use frameless buttons and theme-aware colors: green for adding/saving/resuming, red for removing/pausing, and gray for ordinary dismissal.
- **Compact icon feedback** — Icons keep their touch targets while using smaller press feedback and a borderless selected background for toggles that are on.
- **Help and Legend** — Help & tutorials opens the grouped Legend as a dialog, includes the added icons and controls, and provides a persistent scrollbar when needed.

### Less disruption

- **Stable lists and taps** — Live updates preserve row identity and keep an action attached to the domain originally touched.
- **Preserved screen state** — Rule changes retain app/domain searches, expanded apps and scroll position, and restoring a search does not automatically open the keyboard.
- **Traffic history stays intact** — Editing global block rules no longer clears the recorded access attempts.
- **Clearer live-update controls** — Pausing displayed updates is clearly separated from pausing protection, and clearing access history sits beside the app Pause and settings controls.
- **Navigable raw rules** — Both the raw editor and Rules list have literal Find, previous/next matches and a draggable scrollbar at the far-right edge. Rules-list Find works within the selected rule filters.

## 5. Notifications

- **Allow from a notification** — A blocked-domain notification can queue an allow for its specific app and domain, honoring the delay and requiring in-app review where an explicit block prevents it.
- **Optional global-block alerts** — Notify on global blocks controls whether global blacklist matches appear in access notifications and is off by default.

## 6. Backup

- **Google device backup** — An Automatic backup toggle integrates rules, pending changes and settings with Android's built-in backup service, excluding traffic logs and downloaded APKs.
- **Backup guidance** — The backup screen links to phone backup settings and explains account selection, timing and restoration through an information dialog.

Android controls backup timing and restoration; this does not create Google Drive files or provide a fixed backup schedule.

## 7. App identity, updates and distribution

### Installing and updating

- **DomainGuard branding** — The app and repositories use DomainGuard, with genuine HeartGuard/NetGuard attribution retained.
- **Updates inside the app** — The update screen checks this fork's public releases, and Install update downloads the APK, verifies it and opens Android's installation confirmation.
- **Update history** — Browse releases newest first, with full versions and dates.
- **Upgrade continuity** — The fork keeps its Android package identity and signing key so new releases update previous fork installations without discarding their settings.

### Availability and maintenance

- **Free fork features** — Remaining inherited purchase checks, billing screens and Pro gates were removed from the available fork features.
- **Direct APK and source downloads** — Public releases provide a phone-downloadable APK, its corresponding GPL source archive and checksums. The [source repository and development history](https://github.com/phooBarred-Apps/DomainGuard) are also public.
- **Release and timer safeguards** — Release-build command handling, APK integrity/signature checks and timed-connection enforcement have dedicated fixes and verification coverage.
