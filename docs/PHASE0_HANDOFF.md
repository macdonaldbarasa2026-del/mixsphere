# Phase 0 Status and Handoff (accurate repository state)

Owner: Software Engineer
Date: 2026-11-22
Status: **Gate not met — implementation in review, CI pending**

This document supersedes `docs/PHASE0_STATUS.md`, whose environment claims were
written before repository access was established and are no longer accurate.
The fork, branches, PRs and GitHub Actions all exist and were verified through
the GitHub API.

## Verified facts

- Fork: `macdonaldbarasa2026-del/mixsphere`, is a true fork of
  `mixxxdj/mixxx`, default branch `main`, upstream `main` at fork time
  `825e4519`.
- Upstream version: Mixxx `2.7.0-alpha`, CMake based, 6391 tracked files,
  624 sources in `mixxx-lib`, 140 engine files.
- Upstream already has an Android build path: API 35, NDK `27.2.12479018`,
  vcpkg triplet `arm64-android`, `packaging/android/`, `tools/android_buildenv.sh`.
- A previous non-fork PWA repo was renamed
  `mixsphere-legacy-pwa-archived` and excluded. It could not be deleted because
  the OAuth token lacks `delete_repo` and device flow is unavailable in a
  non-TTY environment; the deletion command is in ADR-0001.

## Delivered in PRs

| PR | Branch | Contents |
|---|---|---|
| #3 | `feat/phase0-mixsphere-rebrand` | App identity `org.mixxx` -> `com.mixsphere`, label Mixsphere, NOTICE with Mixxx attribution, patch register |
| #4 | `feat/phase0-native-spike` | Architecture/analysis docs, ADRs 0001-0007, native engine (Oboe + JNI + Mixxx xfader + real WAV + keylock), Kotlin host, host tests, instrumented proof-of-life test, CI workflow |

PR #2 (`fix/phase0-android-apk-target`) is a stale, conflicting variant of the
baseline docs/workflow and must not be merged.

## CI state

`Mixsphere Phase 0 - Android engine spike` workflow has jobs for:
C++ host GTest, Kotlin JVM tests, static placeholder policy, NDK/Gradle arm64
APK build, and an API 35 arm64 emulator proof-of-life with logcat upload.
Runs were still queued/pending at the time this document was written. **No pass
result, latency number, or APK artifact can be claimed yet.**

## Gate checklist

- [x] Fork created, architecture and dependency analysis written from real source
- [x] ADRs recorded, including feasibility limits (ABI, keylock)
- [x] Native engine, JNI, Kotlin host and tests implemented in a PR branch
- [x] CI workflow defined with pinned versions and artifact uploads
- [ ] CI green: host tests, Kotlin tests, NDK build, APK artifact
- [ ] Emulator logcat shows `PHASE0_PROOF_OF_LIFE=PASS` with latency numbers
- [ ] PR reviewed and merged; patch register finalised

## Risks

1. Full `mixxx-lib` extraction feasibility run was cancelled; no evidence the
   primary strategy works. The implemented path is the documented fallback.
2. Only arm64-v8a is built; armeabi-v7a is infeasible with the pinned vcpkg/Qt
   environment (ADR-0007).
3. Keylock is spike-grade granular OLA, not Mixxx RubberBand (ADR-0006).
4. Emulator latency is not device-representative.

## Next handoff

Wait for CI to complete, fix any compile/test failures in follow-up commits,
then report the gate result with real logs and the APK link. Do not start Phase
1 until that report is accepted.
