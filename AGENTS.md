# DomainGuard contribution guidance

- Every change to `main` must go through a pull request. Do not push commits directly to `main`.
- When Mark authorizes a push, push the working branch, create a pull request, review the diff, run the checks appropriate to the change, and merge the pull request if they pass. Do not ask for a second merge approval.
- Explicit holds such as "don't push", requested preview approval, or pending manual testing take precedence. Leave the pull request unmerged until the hold is resolved.
- Use one pull request per coherent change and a descriptive branch name. Never create or rename a branch with the `codex/` prefix.
- The source repository is `phooBarred-Apps/DomainGuard` (local remote `mw`). Remote `origin` is the upstream HeartGuard repository; do not push or create upstream pull requests without a specific request.
- Use a squash merge and verify the pull request's current head before merging. Wait for applicable checks and stop on failures, conflicts, unresolved review concerns, or unexpected changes.
- GitHub's native auto-merge and enforced private-repository reviews are unavailable on the current free organization plan. Perform the authorized merge through GitHub; do not present this workflow as a server-enforced protection or submit a self-approval.
- Report the pull request link and verification result after merging.
- APK publication requires release authorization. Publish from the merged source revision, preserve the existing Android application ID and signing key, and include the corresponding source archive. Merging a workflow or documentation change does not require a new APK.
- Use `scripts/publish-release.py` for APK publication and follow `docs/releases.md`. Maintain this reusable publisher instead of creating a script for each version. It must never push source changes directly to `main`.
- Use `scripts/preview_pdf.py` and `scripts/publish-preview.py` for numbered PDF reviews; see `docs/previews.md`. Reuse these tools rather than copying a renderer, uploader, or GitHub client per review.
