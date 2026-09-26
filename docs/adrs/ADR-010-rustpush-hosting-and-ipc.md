# ADR 010: OS-Managed rustpush Hosting and IPC Socket

**Status:** Spike decision — unsupported in the current SDK release

**Date:** 2026-09-26

**Tracking:** [GitHub issue #11](https://github.com/sean-light-dev/light-imessage/issues/11), follow-up to [issue #7](https://github.com/sean-light-dev/light-imessage/issues/7)

## Context

Milestone 3 needs rustpush to hold the Apple push connection and expose local IPC at
`rustpush_ipc`. The repository contains a Kotlin `NativeServiceClient` and a Rust
`native-service` transport scaffold, but those artifacts do not by themselves define who
starts or supervises the native process.

The Light SDK is a generated-manifest sandbox. Tool code may not use `android.app.*`,
custom services, receivers, or service binding, and `tool/lighttool.toml` currently has no
native-service capability. The plugin does allow the `native-service` module to build NDK
artifacts and allowlists bundled native libraries, but an APK library is not an Android
process launcher. The current Rust library exports only an ABI probe; its binary entry
point is not launched by the tool and its Apple-facing handlers remain stubs.

Evidence:

- [ADR 005](./ADR-005-unifiedpush-notifications.md) says the bridge must be hosted outside
the tool sandbox and notes that packaging/launch wiring was not complete.
- [Migration status](../initiatives/v1/migration-status.md) records the IPC client as
implemented but unwired, native packaging as pending, and no native process as existing.
- [Milestone 3 migration note](../initiatives/v1/codespec/milestone-3.md) records the same
SDK constraint and distinguishes the client contract from deployment.
- `tool/src/main/kotlin/.../NativeServiceClient.kt` and
`native-service/src/protocol.rs` agree on the current transport: abstract AF_UNIX socket
`rustpush_ipc`, 4-byte big-endian length-prefixed UTF-8 JSON, maximum 16 MiB frame, and
one response per serialized command.
- `native-service/src/socket.rs` binds the abstract socket and `native-service/src/main.rs`
serves one client at a time, but does not provide Android/LightOS lifecycle integration.

## Decision

Neither tool-hosted path is supported today:

1. **Do not implement a custom `android.app.Service` or an in-process tool launcher.** It is
   prohibited by the SDK policy and would make lifecycle ownership dependent on a tool APK.
2. **Do not treat a bundled `.so` as a runnable service.** Bundling is a permitted build
   artifact, not evidence that LightOS will load, execute, or supervise it.
3. **Target a LightOS-owned native-service capability.** LightOS, not the tool, must own the
   rustpush process and expose a capability/registration API for an approved native service.
   The exact supervisor implementation (dedicated executable, extracted native binary, or
   OS-managed loader) is an OS decision and is intentionally not invented here.

### Minimum temporary contract

Until that capability exists, rustpush IPC is a **transport contract only**, not a supported
runtime feature. The minimum contract for the eventual capability is:

- **Process lifecycle owner:** the LightOS native-service supervisor. It owns one rustpush
  instance per device/service identity, not the tool process.
- **Startup:** the supervisor starts the service at boot for a registered push-capable tool,
  or on the first OS-level request for that capability. Tool startup only connects; it never
  starts a process. Startup must complete the socket bind before reporting ready.
- **Socket ownership and namespace:** rustpush owns and binds the abstract AF_UNIX
  `rustpush_ipc` listener. There is no filesystem socket, pathname, or APK-private socket
  to share. The OS must namespace/qualify the name if more than one service instance can
  exist; otherwise the current singleton name is the contract.
- **Access permissions:** the OS must restrict connection to the registered LightOS tool
  identity (prefer same-UID or an equivalent kernel/OS policy). Abstract sockets have no
  filesystem mode bits, so `chmod`/directory permissions are not an access-control plan.
  The supervisor must prevent arbitrary applications from connecting.
- **Restart and failure:** bind failure or process exit is reported to the OS supervisor;
  the supervisor retries with bounded exponential backoff and records a terminal unhealthy
  state after its retry budget. The Kotlin client treats connect/read/heartbeat failure as
  unavailable and may retry using its existing 1–32 second, five-attempt policy. It must
  fall back to the existing relay/periodic sync path rather than claim push delivery.
- **Shutdown:** LightOS sends a graceful termination signal when disabling/uninstalling the
  capability or shutting down the device, waits for a bounded drain period, then force-kills
  the process. Dropping the listener must reclaim the abstract socket; a later start must
  be able to bind the same name.
- **IPC behavior:** retain the current framing and `protocol.rs` tagged JSON contract. A
  successful `PING`/`PONG` proves transport readiness only; it does not imply that Apple
  activation, send, receive, or UnifiedPush handling is implemented.

## Required changes before this can be enabled

These are platform/SDK changes, not work for a tool-side Android service:

1. **LightOS:** provide and document a native-service supervisor with start-on-boot/on-demand,
   restart, shutdown, logging, resource limits, and failure-state semantics; reserve and
   protect the `rustpush_ipc` abstract namespace.
2. **SDK:** expose a sanctioned native-service client/registration capability (or an OS RPC
   that returns readiness and status), including lifecycle and unavailable-state APIs. The
   existing raw `NativeServiceClient` can remain the wire-level implementation underneath.
3. **Manifest/tool metadata:** add an allowlisted capability such as
   `rustpush-native-service` that declares the service identity, ABI(s), startup policy, and
   required socket contract. The generated manifest must carry only OS-recognized metadata;
   it must not permit a tool to declare an arbitrary Android service.
4. **Packaging/build:** define how the `native-service` output is installed for the OS
   supervisor, verify the ARM64 artifact and rustpush feature set, and make the artifact
   available to the LightOS image/tool manager. `cargo-ndk` and APK `jniLibs` wiring alone
   do not provide process startup.
5. **rustpush:** replace the current `ACTIVATE`, `SEND_MESSAGE`, and `GET_MESSAGES` error
   stubs and implement the push/UnifiedPush bridge before advertising the capability as
   production-ready.

## Consequences

- The current repository can continue testing the Kotlin/Rust framing contract without
  pretending that a tool can host or supervise rustpush.
- Push delivery remains unavailable through rustpush in the SDK tool; relay and periodic sync
  are the temporary fallback.
- A future implementation must be reviewed at the OS/SDK boundary first. It must not add a
  custom `android.app.Service`, arbitrary manifest entry, or tool-owned process launcher.
- The abstract socket has no file permissions; identity enforcement is a platform concern and
  is a release blocker, not a detail to defer to the Kotlin client.

## Revisit criteria

Revisit this ADR when LightOS documents the native-service capability and provides a test
image or emulator fixture that can start the service, restrict `rustpush_ipc` access, restart
it, and observe graceful shutdown. At that point add an integration test for lifecycle,
permissions, and reconnect behavior before wiring `NativeServiceClient` into `AppServices`.
