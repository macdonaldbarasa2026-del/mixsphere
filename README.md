# Mixsphere DJ

A from-scratch two-deck DJ app for Android. Original Kotlin audio engine and
original UI; no Mixxx or Cross DJ code, artwork, icons, fonts or samples are
used. Built and signed entirely in GitHub Actions — never on the phone.

## What is here

- `mixsphere-app/` — the complete Android project.
  - Two decks with real waveforms, jog wheels, cue/play/sync/key-lock,
    tempo faders, 8 hot cues per deck and beat-based loops.
  - Centre mixer with EQ/LOOP/FX/SMP/CUE mode panels, per-deck and master VU,
    and an equal-power crossfader.
  - Device library browser (loads any format Android can decode).
  - 16 bundled CC0 transition samples.
  - Original adaptive launcher icon.
- `.github/workflows/mixsphere-build.yml` — builds a signed release APK and
  uploads it as the `mixsphere-dj-release` artifact.

## Building

Push to `main` (or run the workflow manually). The APK appears under the
workflow run's **Artifacts** section.

### Signing secrets (repository → Settings → Secrets and variables → Actions)

| Secret | Value |
| --- | --- |
| `MIXSPHERE_KEYSTORE_BASE64` | base64 of your own upload keystore (`.jks`) |
| `MIXSPHERE_STORE_PASSWORD` | keystore password |
| `MIXSPHERE_KEY_ALIAS` | key alias |
| `MIXSPHERE_KEY_PASSWORD` | key password |

Generate your own upload key:

```
keytool -genkeypair -v -keystore mixsphere-release.jks -alias mixsphere \
  -keyalg RSA -keysize 2048 -validity 10000
base64 mixsphere-release.jks | tr -d '\n'
```

For local debugging only, copy `keystore/keystore.properties.template` to
`keystore/keystore.properties` and point it at your keystore.

## Play Store checklist

1. Use your own upload key (above) and enrol in Play App Signing.
2. Bump `versionCode`/`versionName` in `mixsphere-app/app/build.gradle.kts`.
3. Build the release APK or AAB, upload to the Play Console.
4. Add a privacy policy URL, store listing assets and target API declaration.

## Licensing

The application source in `mixsphere-app` is proprietary and closed source.
Third-party components retain their own licenses — see `NOTICE.txt`.
