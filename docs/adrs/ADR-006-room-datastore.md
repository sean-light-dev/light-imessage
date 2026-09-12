# ADR 006: Room and DataStore for Persistence

**Status:** Accepted

**Context:**
The tool requires structured caching (messages, threads, contacts) and secure storage of session tokens and encryption keys.

**Decision:**
Use `androidx.room` for the relational message cache and `androidx.datastore` (encrypted) for key-value secrets.

**Consequences:**

- **Positive:** Room integrates with `kotlinx.coroutines` Flow; DataStore is the modern Android standard for typed, safe preferences.
- **Negative:** DataStore encryption keys are managed by the Android Keystore, which is hardware-dependent and must be verified on the Light Phone III specifically. **Mitigation:** Phase 7 hardware testing validates Keystore availability; if unavailable, fall back to plaintext preference encryption via `javax.crypto` AES-256-GCM (documented in Phase 4 auth implementation).

> **Amendment (SDK migration, 2026-09):** `androidx.security:security-crypto` (`EncryptedSharedPreferences`) was not on the SDK dependency allowlist at migration time, so `EncryptedTokenRepository` implements the fallback path directly: DataStore preferences with each value encrypted under an AndroidKeyStore-resident AES-256-GCM key and a fresh random IV per write. The `ITokenRepository` interface is unchanged.

> **Amendment (2026-09, revert attempt):** `security-crypto` was allowlisted afterwards, and a revert to `EncryptedSharedPreferences` was attempted. It failed: `EncryptedSharedPreferences.create()` and `MasterKey.Builder()` require a raw `Context`, but the SDK keeps `SealedLightContext.androidContext` `internal` (a compile-time visibility error, not just a scanner rule), tool code may not import `android.content.Context`, and reflection is banned — so `EncryptedSharedPreferences` is structurally unusable by tools until the SDK exposes a sanctioned prefs/Context handle. The hand-rolled DataStore + AndroidKeyStore implementation therefore stays, and `security-crypto` remaining on the allowlist only helps future tools once such a handle exists.
