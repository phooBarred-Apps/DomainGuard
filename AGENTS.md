# DomainGuard contribution guidance

Read these contribution and product rules before making changes in this repository.

- Every change to `main` must go through a pull request. Do not push commits directly to `main`.
- When Mark authorizes a push, push the working branch, create a pull request, review the diff, run the checks appropriate to the change, and merge the pull request if they pass. Do not ask for a second merge approval.
- Explicit holds such as "don't push", requested preview approval, or pending manual testing take precedence. Leave the pull request unmerged until the hold is resolved.
- Use one pull request per coherent change and a descriptive branch name. Never create or rename a branch with the `codex/` prefix.
- The source repository is `phooBarred-Apps/DomainGuard` (local remote `origin`). Remote `upstream` is the original HeartGuard repository; do not push or create upstream pull requests without a specific request.
- Use a squash merge and verify the pull request's current head before merging. Wait for applicable checks and stop on failures, conflicts, unresolved review concerns, or unexpected changes.
- GitHub's native auto-merge and enforced private-repository reviews are unavailable on the current free organization plan. Perform the authorized merge through GitHub; do not present this workflow as a server-enforced protection or submit a self-approval.
- Report the pull request link and verification result after merging.
- APK publication requires release authorization. Publish from the merged source revision, preserve the existing Android application ID and signing key, and include the corresponding source archive. Merging a workflow or documentation change does not require a new APK.
- Use `scripts/publish-release.py` for APK publication and follow `docs/releases.md`. Maintain this reusable publisher instead of creating a script for each version. It must never push source changes directly to `main`.
- Use `scripts/preview_pdf.py` and `scripts/publish-preview.py` for numbered PDF reviews; see `docs/previews.md`. Reuse these tools rather than copying a renderer, uploader, or GitHub client per review.

## Product rules

- **D1 - Reset uses saved defaults.** The main reset control clears searches and restores saved filter defaults while preserving sorting. It must not overwrite those defaults. Session filter changes remain separate from Set as default.
- **D2 - Rule changes preserve history and screen state.** Rule changes must preserve recorded traffic, app/domain searches, expanded apps and scroll position. Restoring a search must not steal focus or open the keyboard.
- **D3 - Global and per-app domain searches remain independent.** Main domain search determines matching apps. A per-app search filters that app's domain rows without removing an otherwise matching app. Show a local empty-result message when appropriate. Copy domains respects both searches, Hide allowed and displayed row limits, and removes duplicates.
- **D4 - Domain taps remain attached to the original domain.** Live updates must not change the app/domain targeted by an interaction already in progress. Preserve the selected domain's identity through its action dialog.
- **D5 - Live updates does not mean logging or protection.** Live updates pauses displayed traffic refreshes only. Labels and explanations must distinguish this from logging, clearing history and pausing DomainGuard protection.
- **D6 - Preserve DomainGuard's timing policy.** Pauses and temporary rules use the existing delay policy. Their duration starts on activation and is limited to seven days. Preserve underlying permanent rules and deadlines across restarts. Delay reductions retain the existing difference-based wait: six hours to four hours waits two hours.
- **D7 - Preserve the parent-domain picker's intended boundaries.** The parent-domain picker must offer the approved shared-hosting parent choices, including githubusercontent.com, while excluding registry endings such as com and co.uk. Keep regression coverage for the exact reported hostname and relevant boundary cases.
