# Migration Status & Drift Register — 2026-09

Audit of the v1 milestone documentation against the code migrated from `light-message`
(`com.lightphone.imessage`, standalone Android app) into this repo's `tool` module
(`com.thelightphone.lightimessage`, Light SDK tool) on branch `v1`.

**How to read this:** each milestone spec keeps its original text as the design record, with
"Migration note" annotations where reality diverged. This file is the consolidated register:
what's implemented, what changed shape, what's pending, and which spec statements were wrong
even before the migration.

## Component status by milestone

### Milestone 2 — Core Service & Data Model

| Scope item | Status |
| --- | --- |
| Auth state machine + token persistence | **Implemented** (`AuthManager` + `AuthStateMachine`) |
| Mac relay HTTP/WebSocket client (OkHttp) | **Implemented** (`RelayHttpClient`, `RelayService`) |
| rustpush IPC client | **Implemented as code, unwired** — `NativeServiceClient` (framing, 30s heartbeat / 5s pong timeout, ≤100 queue, correlation IDs, 1s→32s backoff ×5) has zero call sites; no native process exists |
| `MessageCodec` (Plist + AES-GCM) | **Implemented** |
| `RelayService` | **Implemented** |
| `AuthManager` | **Implemented** (nothing calls `startAuthentication` yet) |
| Room entities (5) | **Implemented** (`Message`, `Thread`, `Contact`, `Attachment`, `DomainEvent`) |
| Encrypted token store | **Adapted** — DataStore prefs + AndroidKeyStore AES-256-GCM per-value IV, not EncryptedSharedPreferences (ADR-006 amendment) |
| `BackgroundSyncWorker` | **Adapted** — `@LightJob backgroundSync` via `LightWork`; network/battery constraints and custom backoff dropped (ADR-008 amendment) |
| `PushReceiver` | **Adapted** — SDK `LightPushService` → `ImessageEntryPoint.onPushNotification` → `PushProcessor` (ADR-005 amendment) |

### Milestone 3 — rustpush APNs & UnifiedPush Bridge

| Scope item | Status |
| --- | --- |
| rustpush packaging/deployment | **Pending** — `native-service/` Gradle shell (sources + lockfile), `rustpush` submodule @ `70ec162`; no cross-compile, no `.so` in APK |
| APNs TLS in rustpush | **Pending** — Rust handlers stubbed |
| One-time attestation | **Adapted** — via `ProvisioningHttpClient` (HTTPS), not via rustpush IPC |
| UnifiedPush bridge in rustpush | **Pending** — and the Kotlin receive side is now SDK-owned |
| `NativeServiceClient` + heartbeat | **Implemented as code, unwired** — single-pong-timeout reconnect (not 3-miss); states `Disconnected/Connecting/Connected/Reconnecting/Failed` (no launcher states) |
| Kotlin `PushReceiver` | **Removed by sandbox** — see ADR-005 amendment |
| `PushHandler` (4 push types) | **Partially adapted** — `PushProcessor` handles delivery only; payloads carry no `type` field |
| IPC events (`MESSAGE_RECEIVED`/`ACTIVATION_STATUS`/`ERROR`) | **Pending** — defined in Rust, not consumed by Kotlin (no `observeEvents`) |
| IPC commands (`SEND_MESSAGE`/`ACTIVATE`/`PING`) | **Pending** — Kotlin client speaks the M2-era command set; wire formats don't match (see drift register below) |
| Room persistence on push | **Implemented** via `PushProcessor` |
| WorkManager deferred sync | **Adapted** — periodic-only; no expedited per-push sync |

### Milestone 4 — Auth & Session

| Scope item | Status |
| --- | --- |
| `AppleIdAuth` orchestration | **Adapted** — `AuthManager` + `AuthStateMachine`; unwired (no UI calls it) |
| State machine + retry guards | **Implemented** — 3 login attempts (1s/2s/4s), 3 2FA attempts, 3 resends, refresh 1s→60s cap |
| `SessionRepository` | **Adapted** — `EncryptedTokenRepository`; no `AuthAttempt` audit trail |
| `HardwareInfoCollector` (serial/UDID) | **Pending** — sandbox bans the `Build.*` surface it would need |
| `RelayActivationClient` | **Adapted** — `RelayHttpClient` + `ProvisioningHttpClient`, different endpoints |
| `NativeServiceClient` ACTIVATE integration | **Dropped by design** — provisioning moved to HTTPS |
| `AuthViewModel` + auth screens | **Pending** — auth-state banner on `ConversationListScreen` only |
| Auto session resume + proactive refresh | **Pending** — expiry enforced lazily on read; no scheduler; no `sinceToken` persisted |
| Input validation | **Partial** — 2FA 6-digit check only |

### Milestone 5 — Messaging Service

| Scope item | Status |
| --- | --- |
| `MessagingService` orchestration | **Pending** — `RelayService.sendMessage` exists, called only by the sync job's retry loop |
| `MessageRepository` / `ThreadRepository` / `ContactRepository` | **Implemented** (Room + Flow) |
| `DraftRepository` | **Pending** |
| `AttachmentManager` | **Pending** — entity/DAO only; attachment AES-GCM decryption never exercised (stub test) |
| `ReadReceiptService` / `TypingIndicatorService` | **Pending** — local-only `markAsRead`; no receipt events exist in `RelayCommand` |
| `MessageComposer` / `ThreadSynchronizer` / `MessageStatusUpdater` | **Pending** — thread upsert is inline in `PushProcessor` |
| `ConversationListViewModel` / `ThreadViewModel` | **Adapted** — thin `LightViewModel`s over repository Flows; no effects surface |
| `MessagePayloadCodec` (IPC JSON) | **Superseded** — the codec that exists is the Apple Plist/AES-GCM envelope codec, in Kotlin |
| `SyncManager` with `sinceToken` | **Adapted** — `BackgroundSyncJob` + `requestSync()` (no token) |

### Milestone 6 — UI & Keyboard

| Scope item | Status |
| --- | --- |
| `ConversationListScreen` | **Adapted (partial)** — landed early during migration; Room-backed list + auth banner; no unread counters, timestamps, mute/archive |
| `ThreadScreen` | **Adapted (partial)** — read-only history, mark-read on show; no keyboard/send/indicators |
| `AttachmentViewer` | **Pending** |
| `SettingsScreen` / `SettingsViewModel` | **Pending** |
| `LightScreen` navigation | **Implemented (subset)** — `navigateTo` + default back; no result routing |
| `Lp3Keyboard` integration | **Pending** — prefer SDK's bundled `LightEmbeddedLp3Keyboard` (ADR-007 amendment) |
| E-Ink optimization | **Partial** — SDK `LightTheme` monochrome; 5000-char limit unenforced (no composer) |
| Accessibility / hardware keys | **Pending** — SDK defaults only |
| §7 UI test matrix | **Blocked** — `androidx.test` is banned; needs a different strategy |

## Sandbox adaptations (migration-driven drift)

1. **No custom Application/Activity/Service/Receiver.** DI moved from `ImeApplication` +
   `AppWorkerFactory` to `di/AppServices`, a lazy locator built from the first
   `SealedLightContext`. Manifest is generated from `tool/lighttool.toml`.
2. **Push:** `PushReceiver` + `PushProcessingWorker` → `PushProcessor`, invoked inline from
   `@EntryPoint.onPushNotification`. Cold-process pushes (before `AppServices` exists) are
   logged and recovered by the next periodic sync.
3. **Background work:** `BackgroundSyncWorker` → `@LightJob` + `LightWork` (WorkManager
   underneath, no constraint API).
4. **Secrets:** `EncryptedSharedPreferences` → DataStore + AndroidKeyStore AES-256-GCM
   (`security-crypto` was allowlisted afterwards; the hand-rolled impl was kept).
5. **Tests:** junit → `kotlin.test`, mockito → hand-written fakes (`domain/auth/Fakes.kt`),
   BouncyCastle → `testing/TestCertificate.kt` stub. *(Reverted 2026-09 on v1 (`844974a`):
   mockito and BouncyCastle were allowlisted post-migration, so the original mockito/BC test
   sources are restored verbatim.)* `RelayServiceIntegrationTest` and the `androidTest` suite
   remain unmigrated — `mockwebserver` and `androidx.test` are still banned.
6. **Permissions:** tool declares INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS,
   WAKE_LOCK. Dropped as unallowlisted: READ/WRITE_EXTERNAL_STORAGE, BIND_JOB_SERVICE.
   RECEIVE_BOOT_COMPLETED is allowlisted but not yet declared.

## Pre-existing drift (wrong before the migration)

Recorded here so future implementers don't trust the old text; annotations added in place where
practical.

- **Dedup window that never existed:** old `PushReceiver` KDoc advertised "dedup against a
  30-second window"; the code only ever did `enqueueUniqueWork(KEEP)` keyed by messageId.
  Current dedup is existence-based (`MessageDao.existsById`).
- **Endpoint schemes:** docs variously cite `/api/v1/activate`, `/api/v1/auth/activate`,
  `/api/v1/auth/verify-2fa`; the code posts to `/relay/login`, `/relay/verify-2fa`,
  `/relay/resend-2fa`, `/relay/refresh-token`, `/provisioning/register-hardware`,
  `/provisioning/activation-status` (against a placeholder base URL).
- **Status enum:** milestone-3/design docs say `1=SUBMITTED`; the code defines
  `1=ENCRYPTED` (`PushProcessor` constants). Corrected in milestone-3 §2.
- **Thread ID:** specs say SHA-256 truncated to 16 bytes; the code uses
  `UUID.nameUUIDFromBytes` (MD5-based). Corrected in design.md §3.
- **Reconnect policy:** design.md §2.1 says 60s cap, unlimited attempts; the code is 32s cap,
  5 attempts, then terminal `Failed`. Annotated in place.
- **Heartbeat tolerance:** milestone-3 is internally inconsistent (invariant: 1 miss = lost;
  state machine/tests: 3 misses). Code implements 1 miss. Annotated in milestone-3 §5.1.
- **Dead links:** design.md links to `docs/initiatives/schemas/*` and `docs/initiatives/specs/*`
  — never committed. Marked in place.
- **Interface renames that never landed:** milestone-2 §3 diagram showed `IRelayClient` /
  `INativeServiceClient` while §11 claimed the rename to `IRelayHttpClient` /
  `IProvisioningClient` was done. Diagram corrected.
- **codespec/index.md** described M4/M5/M6 as UI/Attachments/Hardening — realigned to the
  actual spec files (Auth & Session / Messaging Service / UI & Keyboard).
- **Attachment pipeline is unowned:** milestone-2/-3/-4 all defer it to "Milestone 5", whose
  actual spec scopes it out. Corrected to "follow-up work" everywhere.

## Open issues surfaced by the audit (code-level, not docs)

- ~~**Wire-format mismatch**~~ — **fixed on v1 (`6e1f547`):** `NativeServiceClient` now speaks
  protocol.rs's contract (serde-tagged `"type"` frames, lockstep request/response, 16 MiB cap),
  pinned byte-for-byte by `IpcProtocolTest`.
- ~~**`MessageDao.getUndelivered` over-matches**~~ — **fixed on v1 (`9f873a8`):** query is now
  `isOutgoing = 1 AND status NOT IN (2, 3, 4)` — only outgoing DRAFT/ENCRYPTED/FAILED retry.
- ~~**Socket namespace disagreement**~~ — **fixed on v1 (`6e1f547`):** abstract namespace
  `rustpush_ipc` won (the Rust crate already bound it); the Kotlin client now connects with
  `LocalSocketAddress(..., Namespace.ABSTRACT)`.
