# ADR 006: Room and DataStore for Persistence

**Status:** Accepted

**Context:**
The tool requires structured caching (messages, threads, contacts) and secure storage of session tokens and encryption keys.

**Decision:**
Use `androidx.room` for the relational message cache and `androidx.datastore` (encrypted) for key-value secrets.

**Consequences:**

- **Positive:** Room integrates with `kotlinx.coroutines` Flow; DataStore is the modern Android standard for typed, safe preferences.
- **Negative:** DataStore encryption keys are managed by the Android Keystore, which is hardware-dependent and must be verified on the Light Phone III specifically. **Mitigation:** Phase 7 hardware testing validates Keystore availability; if unavailable, fall back to plaintext preference encryption via `javax.crypto` AES-256-GCM (documented in Phase 4 auth implementation).

> **Amendment (SDK migration, 2026-09):** `androidx.security:security-crypto` (`EncryptedSharedPreferences`) was not on the SDK dependency allowlist at migration time, so `EncryptedTokenRepository` implements the fallback path directly: DataStore preferences with each value encrypted under an AndroidKeyStore-resident AES-256-GCM key and a fresh random IV per write. The `ITokenRepository` interface is unchanged. `security-crypto` was added to the allowlist afterwards (see the develop-branch plugin commit), but the hand-rolled implementation was kept — same security properties, one less dependency.
