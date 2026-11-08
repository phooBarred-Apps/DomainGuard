# Global changes and app delays

A global permission change waits for the default delay. It affects an app at
the later of that global deadline and the app's own deadline, both measured
from confirmation. A shorter app override cannot start a global change early.
A separately confirmed app-only action uses that app's delay and may happen earlier.

A temporary global change has one end time: its global deadline plus its duration.
Longer-delay apps receive only the remaining portion, or none. Expiry and cancellation
prevent later activation; they never create a new app-only rule. An app-only temporary
change receives its full duration after that app's wait. A domain allow permits the
matching domain; an app pause permits all of that app's traffic.

## Accepted examples

Confirm at noon, default delay 10 minutes, Chase override 1 minute, Chrome override 6 hours:

| Action | Chase and default apps | Chrome |
| --- | --- | --- |
| Global pause for 4 hours | Pause 12:10–16:10 | Protected throughout; no change at 18:00 |
| Global pause for 8 hours | Pause 12:10–20:10 | Pause 18:00–20:10 |
| Turn off globally | Off from 12:10 | Off from 18:00 |
| Pause Chase for 4 hours | Only Chase pauses 12:01–16:01 | Unchanged |
| Temporary Chrome allow for 4 hours | Unchanged | Domain allowed 18:00–22:00 |

## Rule-family audit

| Rule or operation | Timing and scope |
| --- | --- |
| Global allow host/IP/direct-IP, including temporary host/IP allows | Default delay, then independent longer app waits |
| Global `blockdomain` removal through Reset, lists or raw editor | Same staging; retained block applies only to apps still waiting |
| Global pause / removal of `feature enabled` | Same staging; VPN stays running until remaining protection is no longer needed |
| Remove `feature manage_system` or `feature lockdown` | Same staging for affected app protection |
| App host/IP allow, whole-package/UID allow, app pause, app block removal | That app's override, or the default; shared Android UIDs use the strongest peer |
| Add blocks / enable protection / resume / cancel a pending permissive change | Immediate restriction; no new permissive wait |
| Remove a normal allow | Immediate; sticky allows retain their delayed-removal contract |
| Global trial-block removal | Existing `try` / `no_try` policy sets the global wait; longer app waits still apply |
| Default/app delay reduction | Existing difference-based wait; 6 hours to 4 hours waits 2 hours |
| Lockdown parameter / static-IP policy changes | Existing policy-setting/reduction timing; global network settings are not per-app network configurations |
| DNS, logging, capture mode, partner configuration and trial policy | Global configuration uses the default or the existing immediate restrictive operation; an unrelated app delay does not lengthen it |
| Ignore / comment | Existing immediate behavior; not a traffic permission |

The parser supports global domain blocks as `blockdomain`; `block host:` and
`block ip:` require a package. This change does not introduce new rule syntax.

## Persistence and interpretation

Schedules store the global deadline and longer app deadlines by package identity,
not device-specific UID. Backups preserve these absolute times and the shared expiry.
Unknown ownership conservatively uses the longest wait; shared UIDs use their strongest
member. DNS lookups may belong to Android's resolver rather than the requesting app.
This change does not remove the existing limitations of DNS and shared-IP attribution.

Old pending rules without saved timing metadata retain their original deadlines.
To use the new policy for one of these, cancel it from All rules and submit it again.
An upgrade must not invent the settings that existed when an old change was confirmed.
Backup schema 3 prevents older implementations from silently ignoring staged removals
or pauses. Schema 1 and 2 backups remain readable.

The main switch fades gently while a global shutdown/pause is pending or active.
Tapping it shows the apps still protected and their dated deadlines. Disabled system
animations are respected. Confirmation actions are Yes/Cancel; dismissing changes nothing.
