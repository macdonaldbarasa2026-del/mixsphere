# ADR-0001: Repository strategy and disposition of the legacy PWA

- Status: Accepted
- Phase: 0
- Owner: Software Engineer / PM

## Context
The account contained a repository named `mixsphere` created before this engagement. Inspection showed it was a React/Vite PWA with a hand-written `djEngine.ts`, a hand-written `MixSphereEngine.cpp`, QML files, and three conflicting Android workflows. It did not contain or derive from Mixxx source. Building on it would violate the non-negotiable rule against fake/mocked engines and would make the required GPL provenance ("fork of Mixxx") false.

The authenticated GitHub token has `repo`, `workflow`, `gist`, `read:org` scopes but not `delete_repo`. Interactive OAuth device flow cannot complete in this non-TTY environment (verified: `gh auth refresh` produced no device code and did not add the scope after repeated attempts).

## Decision
Fork `mixxxdj/mixxx` literally into `macdonaldbarasa2026-del/mixsphere` (default branch only). Rename the legacy repository to `mixsphere-legacy-pwa-archived` and exclude it from all work.

## Consequences
- Positive: true GPL fork provenance; clean source tree; no fake engine code inherited.
- Negative: two repositories exist until the legacy one is permanently deleted.
- Required follow-up (user action, one command on an authenticated terminal):
  `gh auth refresh -h github.com -s delete_repo && gh repo delete macdonaldbarasa2026-del/mixsphere-legacy-pwa-archived --yes`
- The local inspection clone was removed; no local build is performed.
