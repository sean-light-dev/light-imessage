# iMessage Tool Migration (light-message → light-imessage SDK scaffold)

This document records how the partial iMessage client from the standalone
`light-message` repo (`com.lightphone.imessage`, plain Android app) was migrated into this
repository's `tool` module (`com.thelightphone.lightimessage`, Light SDK tool), and the
adaptations forced by the SDK sandbox.

## What moved

All application Kotlin sources from `light-message/tool/src/main` now live under
`tool/src/main/kotlin/com/thelightphone/lightimessage/`, package-renamed:

| Area | Files | Adaptation |
| --- | --- | --- |
| `domain/auth`, `domain/codec`, `domain/crypto`, `domain/push` | state machine, plist codec, envelope codec, JCE crypto | package rename only (pure JVM) |
| `domain/relay` | WebSocket `RelayService`, commands, reconnect policy, `PersistThenAckPolicy` | package rename; legacy ByteArray-based placeholder `IMessageCodec` inside `RelayService.kt` deleted in favor of the real `domain.codec.IMessageCodec` |
| `domain/native` | Unix-socket `NativeServiceClient` + state | package rename; unused `Context` ctor param dropped (tool code may not import `android.content.Context`) |
| `data/dao`, `data/entity`, `data/repository` | Room layer | package rename only |
| `data/database/ImessageDatabase` | Room database | `getInstance(Context)` → `getInstance(SealedLightContext)` via the SDK's `buildDatabase` |
| `data/relay`, `data/provisioning` | OkHttp HTTPS clients | package rename; OkHttp kept (whitelisted, added to version catalog) |

## What was rewritten (sandbox-driven)

| Before (light-message) | After (this repo) | Why |
| --- | --- | --- |
| `ImeApplication` + `AppWorkerFactory` DI | `di/AppServices` lazy service locator, built from the first `SealedLightContext` | `android.app.Application` import is banned; SDK owns the Application class |
| `MainActivity` + `ui/AppNavigation` placeholder | `ui/ConversationListScreen` (`@InitialScreen`) + `ui/ThreadScreen` with `LightViewModel`s | `androidx.activity` is banned; SDK owns the Activity and navigation |
| `push/PushReceiver` + `PushProcessingWorker` | `push/PushProcessor`, invoked from `ImessageEntryPoint.onPushNotification` | `BroadcastReceiver` is banned; SDK routes UnifiedPush payloads to the entry point |
| `domain/sync/BackgroundSyncWorker` | `sync/BackgroundSyncJob` (`@LightJob`), scheduled via `LightWork.enqueuePeriodic` (15 min) from `ConversationListScreen.willShow()` | Workers can't get custom factories; SDK wraps WorkManager in `LightWork` |
| `data/datastore/EncryptedTokenRepository` on `EncryptedSharedPreferences` | Same `ITokenRepository` interface; impl now DataStore + AndroidKeyStore AES-256-GCM (manual, per-value random IV) | `androidx.security:security-crypto` is not on the dependency allowlist — this is the ADR-006 fallback path |
| `AndroidManifest.xml`, `res/` | `tool/lighttool.toml` (manifest is generated) | user manifests are rejected; permissions: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK |
| `lighttool.toml` `versionCode = 0` | `versionCode = 1` | plugin validation requires ≥ 1 |

## What was dropped

- **`ipc/RustpushService`** — `android.app.Service` is banned and the SDK forbids arbitrary native
  libraries, so the rustpush native-service bridge (ADR-005) cannot exist inside an SDK tool. The
  Kotlin-side IPC client (`domain/native/NativeServiceClient`) migrated and compiles, but has no
  in-process counterpart until LightOS offers a native-service capability. The Rust crate
  (`native-service/`) and the `rustpush` submodule were **not** copied — they remain in
  `light-message`.
- **`RelayServiceIntegrationTest`** — needs `okhttp3:mockwebserver` (not allowlisted).
- **`androidTest/` suite** — needs `androidx.test.*` (not allowlisted).
- Old Manifest permissions `RECEIVE_BOOT_COMPLETED`, `READ/WRITE_EXTERNAL_STORAGE`,
  `BIND_JOB_SERVICE` — not in the SDK permission allowlist.

## Known limitations introduced by the sandbox

- `onPushNotification` receives no `SealedLightContext`, so a push that arrives in a cold process
  (before any screen/job ran) cannot touch the database. `ImessageEntryPoint` logs and defers;
  the periodic `background-sync` job re-requests pending messages from the relay, so delivery is
  eventually consistent rather than instant in that corner.
- Codec key material (`CodecKeys` provider in `AppServices`) still returns null until auth
  provisioning lands (pre-existing TODO F-4); inbound envelopes are dropped loudly in that state,
  matching the old pipeline's terminal behavior.

## Tests

`./gradlew :tool:testDebugUnitTest` — 121 tests, 0 failures. Converted: junit → `kotlin.test`,
mockito → hand-written fakes (`domain/auth/Fakes.kt`), BouncyCastle cert generation →
`testing/TestCertificate.kt` stub (production code only reads `certificate.publicKey`).

## Verify

```
just tool-lint tool-test tool-build
```
