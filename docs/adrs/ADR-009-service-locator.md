# ADR 009: Lazy Service Locator over Application-level DI

**Status:** Accepted

**Date:** 2026-09-12

---

## Context

Light SDK tools get no `Application` class: the plugin-generated manifest hardwires `LightSdkApplication`, and it invokes the tool's registered `@EntryPoint` / screens / jobs from within its own code. The pre-SDK `light-message` app scattered its app-wide singleton wiring across an `ImeApplication` subclass plus an `AppWorkerFactory`. Both are banned in the sandbox (`android.app.*` blocked, custom `WorkerFactory` registration unreachable from `LightSdkApplication`), and a `Context`-free tool has no obvious place to hold the `ImessageDatabase`, `OkHttpClient`, repositories, and long-lived services (`RelayService`, `AuthManager`, `PushProcessor`).

## Decision

Converge all app singletons behind a single lazy locator, `di/AppServices`, instantiated from the first `SealedLightContext` the SDK hands to a screen or `@LightJob` handler and cached for the process lifetime. Entry-point callbacks (`onPushNotification`) read through `AppServices.peek()`; when a cold process receives a push before any screen or job has run, we log and defer to the periodic background-sync job rather than crash or block.

## Consequences

### Positive
- One initialization path for screens and jobs alike; no DI framework (fine at this scale — ~8 collaborators).
- Push delivery survives cold-process delivery degraded-but-not-broken (eventual consistency via the sync job).
- All SDK-visible collaborators (`SealedLightContext`-derived: database, DataStore, file share, connectivity) come from the same object, so divergent context handling is impossible.

### Negative
- Cold-process pushes execute before services exist — the deferred-to-sync fallback is an eventual-consistency trade, not a bug workaround.
- Lazy init order is implicit; anything touching `AppServices.get(...)` constructs the whole graph eagerly enough for Room/DataStore setup cost to land on first screen/job.
- Not a real DI container: constructors are hand-wired, so refactorability of individual services depends on discipline, not generated code.

### Migration or Follow-up
If the SDK later exposes an app-level hook owned by `LightSdkApplication` (or a sanctioned `SharedPreferences`/`Context` handle — see ADR-006's revert-attempt amendment), re-evaluate converging singleton creation there instead.

## Alternatives Considered

| Option | Rationale | Why not chosen |
|--------|-----------|---|
| Custom `Application` subclass | Pre-SDK pattern, directly maps `ImeApplication` | Banned by the sandbox (`android.app.*` import); generated manifest hardwires `LightSdkApplication` |
| Per-screen construction | Each screen resolves its own dependencies | Breaks process-wide singletons (Room, OkHttp, services would multiply) |
| DI framework (Hilt/koin) | Generated graph, standard for Android | Needs an `Application` anchor; banned for same reason; adds a dependency for marginal benefit here |
| Leak `SealedLightContext` into a static field directly | Avoids a wrapper class | Same thing as `AppServices`, without the naming auditability — rejected as obscure, not meaningfully simpler |

## References

- ADR-008 amendment (worker DI changed shape to `@LightJob` + `LightWork`)
- `plugin/.../LightSdkPlugin.kt` blocked-imports list (`android.app.Application`)
- `docs/initiatives/v1/migration-status.md` (sandbox adaptation #1)
- `tool/src/main/kotlin/com/thelightphone/lightimessage/di/AppServices.kt`
