# Patch register

This file records every modification applied to upstream Mixxx sources or build files in the Mixsphere fork. No patches have been applied yet; this register is created up front so that any future change is captured at the moment it is made.

| Date | File | Hunk / change | Reason | Commit |
|---|---|---|---|---|
| 2026-11-22 | `CMakeLists.txt:2379` | `ANDROID_PACKAGE_NAME` `org.mixxx` -> `com.mixsphere` | Product rebrand; internal Android data path identity | pending |
| 2026-11-22 | `CMakeLists.txt:2387` | `QT_ANDROID_PACKAGE_NAME` `org.mixxx` -> `com.mixsphere` | Product rebrand; APK application ID | pending |
| 2026-11-22 | `packaging/android/AndroidManifest.xml` | package, label, activity -> Mixsphere | Product rebrand | pending |
| 2026-11-22 | `packaging/android/src/org/mixxx/*` -> `com/mixsphere/*` | package declaration, action, log tag | Product rebrand | pending |

Rules:
- An empty implementation, no-op function, or returned constant is never an acceptable patch.
- Each patch must be accompanied by a test that exercises the changed behaviour.
- Each row references the Conventional Commit that introduced it.
