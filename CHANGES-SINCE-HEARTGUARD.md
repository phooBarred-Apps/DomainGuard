# Changes since HeartGuard

Current in DomainGuard 2.7. This combines the HeartGuard-MW and DomainGuard work, groups related refinements, and leaves out replaced designs and features inherited unchanged from HeartGuard.

## 1. Allowing and blocking internet access

### Creating rules

- **Simpler Allow and Block dialogs** — Tap a domain, choose Allow or Block, and use the globe to apply the rule to all apps instead of just the selected app.
- **Parent-domain picker** — The dropdown attached to the domain field lists every usable parent, so a long address can become a broader rule without manually deleting subdomains.
- **Pasting addresses** — Rule entry accepts supported domains, HTTP/HTTPS URLs and IP addresses, extracts the host, and validates it before saving.

### Rules for every app

- **Central global lists** — Menu > Global lists opens separate whitelist and blacklist dialogs with matching icons, search, bulk entry, pending changes and consistent Back/Done navigation.
- **App-specific block exceptions** — A domain can stay blocked for one app even when a global or whole-app allow would otherwise permit it.
- **Global-rule indicators** — Green and red globe badges distinguish global allows and blocks from app-specific decisions, with explanations in the Legend.

### Temporary rules and delays

- **Temporary Allow and Block** — Either rule can expire after a chosen number of seconds, minutes, hours or days, up to seven days, without deleting the permanent rules underneath it.
- **Rule-delay settings** — A regular settings dialog replaces memorizing raw delay syntax, with unit selection, readable durations, pending-change cancellation and help.
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
- **Domain results in the app list** — Cross-app domain searches keep the normal layout, display matching apps expanded, and retain separate queries when switching modes.
- **Search inside an app** — Each app has its own domain search, hidden behind a magnifying glass by default, with a setting to keep it visible.
- **Copy matching domains** — Cross-app search can copy every distinct matching domain once, including results outside the visible part of the list.

### Filters and ordering

- **Combined app filters** — Filter by allowed/blocked defaults, recorded activity and user/system apps, with options for disabled apps and apps without internet permission.
- **Useful sort choices** — Apps and domains can use relevant name, activity and access ordering, with direction controls and app-ID sorting where applicable.
- **Saved defaults** — Sort and Filter have separate Set as default actions, while ordinary changes remain limited to the current session.
- **Focused count and reset** — The app count and reset control appear only when a search or filter is active, and reset keeps saved defaults and the chosen sort.

## 4. App layout and everyday use

### Expanded apps

- **Domains and Info & controls tabs** — App information and controls are directly accessible without scrolling through the entire domain list, with an option to open that panel by default.
- **Compact domain lists** — An optional height limit of two to ten rows gives longer lists their own scrollbar, with a full-screen sample preview and Back arrow.
- **Separate scrolling areas** — Narrow side strips scroll the whole app screen, while a subtle theme-aware background identifies the inner domain list.
- **Hide allowed domains** — A global display preference supplies the default, and each expanded app can override it for the current session with one icon.

### Appearance and interaction

- **Toolbar preferences** — Search, Filter and Sort use icons, with a setting for whether Sort and Filter always appear in the toolbar, appear when space permits, or stay in the menu.
- **Organized display settings** — Appearance, app-list and domain-list preferences are collected together instead of being scattered among unrelated options.
- **Timestamp choices** — Domain entries can show exact times with day-of-month and seconds, or time elapsed.
- **Consistent controls** — Updated controls use frameless buttons and theme-aware colors: green for adding/saving/resuming, red for removing/pausing, and gray for ordinary dismissal.
- **Help and Legend** — Help & tutorials opens the grouped Legend as a dialog, includes the added icons and controls, and provides a persistent scrollbar when needed.

### Less disruption

- **Stable lists and taps** — Live updates preserve row identity and keep an action attached to the domain originally touched.
- **Preserved screen state** — Rule changes retain app/domain searches, expanded apps and scroll position, and restoring a search does not automatically open the keyboard.
- **Traffic history stays intact** — Editing global block rules no longer clears the recorded access attempts.
- **Clearer live-update controls** — Pausing displayed updates is clearly separated from pausing protection, and clearing access history sits beside the app Pause and settings controls.
- **Navigable raw rules** — The raw editor has literal search, previous/next matches and a draggable scrollbar at the far-right edge.

## 5. Notifications

- **Allow from a notification** — A blocked-domain notification can queue an allow for its specific app and domain, honoring the delay and requiring in-app review where an explicit block prevents it.
- **Optional global-block alerts** — Notify on global blocks controls whether global blacklist matches appear in access notifications and is off by default.

## 6. Backup

- **Google device backup** — An Automatic backup toggle integrates rules, pending changes and settings with Android's built-in backup service, excluding traffic logs and downloaded APKs.
- **Backup guidance** — The backup screen links to phone backup settings and explains account selection, timing and restoration through an information dialog.

Android controls backup timing and restoration; this does not create Google Drive files or provide a fixed backup schedule.

## 7. App identity, updates and distribution

### Installing and updating

- **DomainGuard branding** — Current app names use DomainGuard while genuine HeartGuard/NetGuard attribution and historical HeartGuard-MW release names remain intact.
- **Updates inside the app** — The update screen checks this fork's public releases, and Install update downloads the APK, verifies it and opens Android's installation confirmation.
- **Complete fork history** — Update history includes HeartGuard-MW and DomainGuard releases from the fork onward, newest first, with full versions and dates.
- **Upgrade continuity** — The fork keeps its Android package identity and signing key so new releases update previous fork installations without discarding their settings.

### Availability and maintenance

- **Free fork features** — Remaining inherited purchase checks, billing screens and Pro gates were removed from the available fork features.
- **Direct APK and source downloads** — Public releases provide a phone-downloadable APK, its corresponding GPL source archive and checksums while development remains in the private repository.
- **Release and timer safeguards** — Release-build command handling, APK integrity/signature checks and timed-connection enforcement have dedicated fixes and verification coverage.
