# ADR-0007: Android ABI scope for Phase 0

- Status: Accepted
- Phase: 0
- Owner: Software Engineer

## Context

The environment rules ask for NDK builds on `arm64-v8a` and `armeabi-v7a`.
Evidence from the fork:

- `tools/android_buildenv.sh` selects exactly one vcpkg triplet for Android:
  `arm64-android` (or `arm64-android-rel`), with `ANDROID_API=35` and NDK
  `27.2.12479018`.
- `CMakeLists.txt:81` sets `ANDROID_ABI arm64-v8a` and
  `set_property(TARGET mixxx-lib PROPERTY ANDROID_ABIS "arm64-v8a")`.
- `packaging/android/AndroidManifest.xml` declares USB host as required, which
  is a 64-bit-oriented device profile in practice.

There is no prebuilt vcpkg build environment for `armeabi-v7a`, and Qt for
Android 6.x no longer ships a 32-bit ARM artifact in the standard online
installer channels used by this build.

## Decision

Phase 0 builds and tests `arm64-v8a` only. armeabi-v7a is explicitly out of
scope for this phase.

## Consequences

- The original environment-rule expectation of a 32-bit CI build is not met in
  Phase 0; this ADR is the required evidence-based feasibility statement.
- Alternative for a later phase: cross-build the entire dependency set for
  `armeabi-v7a` from source with a custom vcpkg triplet and an older Qt, or
  drop 32-bit support permanently. Modern Play Store device share makes the
  latter the recommended product decision; it requires PM sign-off.
- APK artifact is 64-bit only and must state this in release notes.
