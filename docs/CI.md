# APK pipeline copied from route

Source: `otdavies/route`, `.github/workflows/android-preview.yml`, inspected at commit `b88a465bf1fa3004ae6319094a636bdbad1a34e3`. Imported as the first commit on android-hdri/main and adapted for a separate app.

Routine pushes run JVM unit tests and Android lint. Markdown-only commits are ignored. Explicit `[release]` commits or manual `verify` / `release` runs build the application and instrumentation APK exactly once. A separate device job downloads those candidates, verifies checksums, installs/reinstalls the app and runs instrumentation directly through adb. The publish job downloads the same checksummed APK; it never rebuilds it.

The route workflow's independent Gradle/SDK caches, bounded emulator startup, per-command adb timeouts, retained paired candidates, job timeouts and separate quick-check/release concurrency groups are preserved. Route's pose models, climbing fixtures and legacy application upgrade checks are removed. The sample scene is generated on device and uses no external dataset.

## Signing

The `HDRI_KEYSTORE_BASE64`, `HDRI_STORE_PASSWORD`, `HDRI_KEY_ALIAS`, `HDRI_KEY_PASSWORD` repository secrets can provide a durable private signing identity. Never copy route's signing key into this repository.

For the initial preview only, the workflow generates a development identity if no release exists and caches it under a fixed repository-specific key. Subsequent builds reuse it. Once a release exists, losing that cache is a hard failure rather than silently changing the app signature. Restore the established key through secrets before continuing. A development key in Actions cache is not an appropriate production signing secret; public CI caches are not a confidentiality boundary. Production distribution needs a separately managed private key and a planned app-ID/signing migration.

Local builds use the local Android debug identity unless signing environment variables are explicitly supplied. They should not be mixed with CI-installed previews if those identities differ.

## Verification and retry

Device verification exercises native OpenCV HDR processing, Radiance decoding, spherical coverage, cancellation checkpoints and visible UI on an AOSP API 36 emulator. It is not a substitute for physical AR/camera tests.

Candidates and device diagnostics are retained for three days. Retry failed jobs to reuse the successful build. A CI-only source fix can reuse `candidate_run_id` or `[reuse:RUN_ID]`; the copied candidate checker rejects Android/test/build source changes and preserves the APK's original source SHA. Publication tags that source SHA.

Only the publish job receives repository contents write permission. The initial signing step only reads release metadata. No scheduled jobs, external device lab or paid runner are configured.
