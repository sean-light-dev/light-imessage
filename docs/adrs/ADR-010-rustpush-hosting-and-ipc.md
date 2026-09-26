# ADR 010: SDK Native-Service Capability for rustpush

**Status:** Proposed — implementation belongs on `develop`

**Date:** 2026-09-26

**Tracking:** [GitHub issue #7](https://github.com/sean-light-dev/light-imessage/issues/7) and [issue #11](https://github.com/sean-light-dev/light-imessage/issues/11)

## Context

Milestone 3 has a tested tool-side `NativeServiceClient` and a Rust IPC scaffold, but no
supported owner for the rustpush process. A tool may not use `android.app.*`, bind or start a
service, or supply arbitrary manifest components. The existing `native-service` Gradle module
only packages `librustpush_service.so` for `arm64-v8a`; a shared library is not a supervised,
runnable Android service.

This is an SDK-expansion request, not a request to evade the sandbox. `develop` already has the
right extension pattern: an allowlisted `lighttool.toml` capability is validated by
`LightToolMetadata`, rendered by `ManifestGenerator`, and consumed by an SDK/OS-owned runtime.
`detached-audio` adds the SDK-owned `LightAudioService`; `tool-manager-provider` adds a
discoverable provider; `LightWork`, push, NFC, and connectivity similarly keep framework
ownership behind SDK APIs.

## Decision

Add one **allowlisted, rustpush-specific native-service capability** on `develop`. LightOS owns
installation, launch, supervision, access control, and teardown. The tool only receives the
typed SDK client and status; it never launches a process, declares an Android service, or opens
an arbitrary socket.

### 1. Tool declaration and generated metadata

Extend the current capability model rather than adding a free-form service declaration:

```toml
[tool]
capabilities = ["rustpush-native-service"]

[nativeService]
id = "rustpush"
protocolVersion = 1
abi = "arm64-v8a"
artifact = "rustpush-service"
startup = "on-demand"
```

`LightToolMetadata` must reject the `[nativeService]` table unless the capability is present,
require all five fields when it is present, and currently accept only the exact values above.
That deliberately prevents a tool from registering an arbitrary executable, ABI, protocol, or
startup policy. The plugin emits these application metadata keys in addition to the normal
`CAPABILITY_RUSTPUSH_NATIVE_SERVICE` marker:

| Key                                                     | Value              |
| ------------------------------------------------------- | ------------------ |
| `com.thelightphone.sdk.NATIVE_SERVICE_ID`               | `rustpush`         |
| `com.thelightphone.sdk.NATIVE_SERVICE_PROTOCOL_VERSION` | `1`                |
| `com.thelightphone.sdk.NATIVE_SERVICE_ABI`              | `arm64-v8a`        |
| `com.thelightphone.sdk.NATIVE_SERVICE_ARTIFACT`         | `rustpush-service` |
| `com.thelightphone.sdk.NATIVE_SERVICE_STARTUP`          | `on-demand`        |

The generated manifest declares **no** tool `android.app.Service`, receiver, provider, intent
filter, permission, or socket name for this capability. LightOS discovers the signed metadata
through its existing SDK/tool scan; it must verify the package signature and permitlist before
registering the descriptor.

### 2. Public SDK API

Expose the capability from `sdk:client`, using the entry-point/application initialization path
already owned by `LightSdkApplication`:

```kotlin
object LightNativeServices {
    fun rustpush(): RustpushService
}

interface RustpushService {
    val status: StateFlow<NativeServiceStatus>
    suspend fun connect(): Result<RustpushClient>
}

sealed interface NativeServiceStatus {
    data object Unavailable : NativeServiceStatus
    data object Starting : NativeServiceStatus
    data object Ready : NativeServiceStatus
    data class Restarting(val attempt: Int, val retryAfter: Duration) : NativeServiceStatus
    data class Failed(val reason: NativeServiceFailure) : NativeServiceStatus
}
```

`RustpushClient` owns the existing framed `PING`, `ACTIVATE`, `SEND_MESSAGE`, and
`GET_MESSAGES` protocol and exposes its connection state; it must not expose a socket path,
`LocalSocket`, process handle, launch method, or a `stop()` operation. `connect()` asks LightOS
to ensure the registered descriptor is running and completes only after readiness is confirmed.
`Unavailable` means unsupported SDK/OS, missing declaration, unsupported ABI, or policy denial;
`Failed` is a diagnosed terminal service failure. `Ready` means a socket is authenticated and a
`PING` receives `PONG`; it does not claim Apple activation or message delivery is ready.

The API is intentionally service-specific for the first release. A generic executable registry
would reintroduce arbitrary tool-controlled native services before there is a second proven use
case.

### 3. LightOS/tool-manager responsibility and artifact identity

Add a LightOS native-service supervisor, invoked by the existing tool manager/SDK discovery
path. It must:

1. validate the signed generated metadata, capability policy, APK version, certificate identity,
   protocol version, SHA-256 of the packaged executable, and device ABI;
2. extract the declared artifact to a supervisor-owned, non-writable directory, verify its digest
   before each launch, and execute it under the service's assigned app identity;
3. launch on the first `LightNativeServices.rustpush().connect()` request (not from tool code),
   expose status, capture bounded logs, enforce resource limits, and restart crashes with a
   documented bounded exponential policy; and
4. stop the process on uninstall, capability revocation, or device shutdown: request graceful
   exit, wait a bounded grace period, then kill and remove the extracted executable.

Replace the current `.so`-only handoff with a separately packaged, uncompressed Android PIE
executable at:

```
assets/light-native/rustpush/arm64-v8a/rustpush-service
```

The artifact is the Cargo `[[bin]]` `rustpush-service` output, not
`librustpush_service.so`; the latter may remain a build/link check but is not the service
artifact. The packaging task produces an adjacent signed descriptor containing the fixed service
id, ABI, protocol version, SHA-256, and artifact path. The plugin verifies this descriptor
against `[nativeService]`; the supervisor verifies it again after installation. Initial support
is `arm64-v8a` only, matching `native-service/build.gradle.kts`; other ABIs are a new,
explicitly tested metadata value and artifact, never a fallback.

### 4. Secure socket contract

The service no longer binds the globally guessable `rustpush_ipc` name. At launch, the supervisor
computes and passes an abstract socket name:

```
ltns.v1.<base32(sha256(packageName || signingCertDigest || "rustpush"))[0..31]>
```

The name has no leading `@`/NUL in metadata; the Rust listener and Android `LocalSocket` use it
in the abstract namespace. It is an implementation-derived endpoint, never a public tool API.
The supervisor passes it as the service's `--socket` argument and gives it only to the SDK client
for that verified registration.

The listener must validate each connecting peer with `SO_PEERCRED` against the package UID
registered by LightOS before reading a frame; all other peers are closed. If a target image
cannot run the service with verifiable peer credentials, this capability is unavailable rather
than falling back to a predictable socket or filesystem permissions. Abstract AF_UNIX sockets
have no filesystem mode bits, so `chmod` is not an access-control design.

Keep protocol version 1: 4-byte big-endian length + UTF-8, serde internally-tagged
SCREAMING_SNAKE_CASE JSON, 16 MiB maximum frame, and one serialized response per command.
A protocol-version mismatch fails registration before launch.

### 5. Lifecycle, readiness, reconnect, and status

The supervisor transitions `Unavailable → Starting → Ready`, reports `Restarting` for its bounded
restart policy, and reports `Failed` with a stable reason code. It must not report `Ready` until
all of these succeed: executable digest verification, process launch, socket bind, permitted SDK
client connection, and `PING`/`PONG`.

`RustpushClient` retains the current client-side 30-second heartbeat, 5-second PONG timeout, and
five reconnect attempts with 1/2/4/8/16-second backoff. Socket read/write failure moves its
connection state to reconnecting and triggers a fresh supervisor status query; a process crash
may therefore be restarted by LightOS while the client reconnects. Exhaustion produces a client
failure while the supervisor may still later recover. Explicitly closing the client disconnects
only that client; it does not stop the OS-owned service. v1 continues to use relay and periodic
sync when status is unavailable or failed.

## Develop-side work and tests

1. **Plugin/metadata:** extend `LightToolMetadata`, validation tests, `ManifestGenerator`, and
   metadata docs. Test absent capability, invalid/missing fields, only the fixed descriptor,
   generated keys, and that no Android service component is emitted.
2. **SDK:** add `LightNativeServices`, `RustpushService`, status/failure models, and a
   capability-marker check patterned after `DetachedAudioCapability`. Test missing marker,
   unavailable/ABI/protocol failures, state transitions, readiness gating, client reconnect, and
   that tools cannot stop or name the process/socket.
3. **Build/package:** make the native module produce the PIE executable plus descriptor; test
   arm64 artifact type, descriptor digest, APK path, tamper rejection, and no `.so` substitution.
4. **LightOS/emulator:** implement the supervisor and fixture. Integration-test discovery,
   on-demand launch, `PING`/`PONG`, denied cross-package connection, crash/restart, client
   reconnect, graceful shutdown/socket reuse, update/uninstall cleanup, and status propagation.
5. **Protocol:** retain the existing Kotlin/Rust framing tests, then add version-mismatch and
   peer-identity tests. Do not advertise activation/send/receive until the existing Rust command
   stubs and UnifiedPush bridge have real end-to-end tests.

## Rollout and compatibility

Ship metadata parsing and the SDK API first, with `Unavailable` on existing LightOS images. Ship
the supervisor and emulator fixture behind the capability allowlist; enable the capability only
for signed, `arm64-v8a`, protocol-1 builds after integration tests pass. Older tools omit the
metadata and behave unchanged. Older SDK/OS combinations fail closed as `Unavailable`; they do
not attempt the old `rustpush_ipc` endpoint. A protocol or ABI change uses a new explicit value
and coordinated supervisor support, preserving the v1 descriptor contract.

## Narrow v1 follow-up after SDK support lands

Do not create a custom `android.app.Service`. Replace `UnavailableNativeServiceCapability` in
`AppServices` with a thin adapter around `LightNativeServices.rustpush()`, and migrate the
existing `NativeServiceClient` behavior to `RustpushClient` rather than duplicating transport.
Wire the already-defined status into the iMessage UI/diagnostics, exercise real `PING`/`PONG` and
reconnect against the emulator fixture, and retain relay/periodic-sync fallback. Separately land
real rustpush activation/send/receive handling before claiming Milestone 3 delivery complete.

## References

- `223458e` — capability-gated tool-manager provider metadata on `develop`
- `a92b4e7` — capability-gated foreground audio and SDK-owned service
- `c1bcbba`, `587d11b`, `601f9d0`, `5dba2fc` — NFC, connectivity, `LightWork`, and push SDK
  extension precedents
- `plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt`
- `plugin/src/main/kotlin/com/thelightphone/plugin/ManifestGenerator.kt`
- `sdk/client/src/main/kotlin/com/thelightphone/sdk/LightSdkApplication.kt`
- `tool/src/main/kotlin/com/thelightphone/lightimessage/domain/native/NativeServiceClient.kt`
- `native-service/build.gradle.kts`, `native-service/Cargo.toml`, and
  `native-service/src/{main.rs,socket.rs,protocol.rs}`
- `docs/initiatives/v1/codespec/milestone-3.md` §§4.1, 4.5, 5.1
