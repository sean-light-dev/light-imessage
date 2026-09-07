# ADR 008: androidx.work for Background Sync

**Status:** Accepted

**Context:**
When the WebSocket is disconnected or the device is dozing, the tool must still retrieve messages. The `androidx.work` library is whitelisted.

**Decision:**
Use `WorkManager` for periodic background sync tasks (e.g., relay health check, message polling) when the push channel is unavailable. WebSocket connectivity will be managed by `OkHttp` in the foreground.

**Consequences:**

- **Positive:** Reliable, battery-aware deferred execution; standard Android architecture.
- **Negative:** Aggressive polling intervals will drain battery; must default to the push channel and treat WorkManager as a fallback only.

> **Amendment (SDK migration, 2026-09):** Tools cannot register a custom `WorkerFactory` (the SDK owns the `Application`), so the pre-SDK `BackgroundSyncWorker` became a top-level `@LightJob` handler (`sync/BackgroundSyncJob.kt`) scheduled via the SDK's `LightWork.enqueuePeriodic` (15 min, from `ConversationListScreen.willShow()`). WorkManager remains the engine underneath, but `LightWork` exposes no constraint API: the old worker's `NetworkType.CONNECTED` + battery-not-low constraints and custom exponential backoff were dropped in favor of `LightJobResult.Retry` (WorkManager default backoff). Dependencies reach the job via the `AppServices` locator instead of constructor injection.
