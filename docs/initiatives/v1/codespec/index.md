# Codespec Index — LightOS iMessage Client

This directory contains detailed technical specifications for each milestone of the LightOS iMessage client project.

## Overview

Each milestone specification defines:

- **Formal Requirements** — goals, scope in/out, invariants
- **Data Model** — entities, relationships, schema
- **Code Architecture** — components, interfaces, patterns
- **Component Interactions** — workflows (send, receive, auth, sync, etc.)
- **Stateful Behavior** — state machines and lifecycle management
- **Algorithmic Logic** — core algorithms and procedures
- **Test Matrix** — unit, integration, edge case, invariant coverage
- **Task Dependencies** — breakdown of work with effort estimates
- **Implementation Timeline** — Gantt chart with internal stories (S1–S5)
- **Revision History** — version tracking and change log

## Milestone Specs

### [Milestone 2: Core Service & Data Model](./milestone-2.md)

**Scope:** Foundation layers — Room database, encryption, codec, auth state machine, relay connection, message encryption/decryption.

**Stories (S1–S5):**

- S1: Data Layer — Room schema, DAO, encrypted token store
- S2: Crypto & Codec — Plist codec, JCE crypto engine
- S3: Core Services — Auth, relay, message codec, native IPC
- S4: Push & Sync — Push receiver, background sync
- S5: Milestone 2 Review — API docs, ADR updates, specification review

**Deliverables:** 14 tasks, 56 hours, 14 artifacts (schema, repositories, crypto, codecs, services).

---

### [Milestone 3: rustpush APNs & UnifiedPush Bridge](./milestone-3.md)

**Scope:** Native integration — `rustpush` service deployment, APNs TLS connection, UnifiedPush bridge, Kotlin IPC client, push handler.

**Stories (S1–S5):**

- S1: Native Service Deployed — rustpush build, APKs, activation
- S2: IPC & Heartbeat Stable — JSON-RPC framing, 30s heartbeat, reconnect
- S3: Auth Delegation Complete — relay activation, cert storage
- S4: UnifiedPush Bridge Live — push routing, message insertion, room persistence
- S5: Milestone 3 Review — native service integration docs, ADR updates

**Deliverables:** 13 tasks, 52 hours, 13 artifacts (native client, IPC, push handler, service contract).

---

### [Milestone 4: Auth & Session](./milestone-4.md)

**Scope:** Apple ID authentication — login, 2FA, session token persistence, hardware provisioning, token refresh, logout.

> **Index correction (2026-09):** this entry previously described a "UI & Compose Layer" milestone; the actual spec file (and proposal phase 4) is Auth & Session. UI is Milestone 6.

---

### [Milestone 5: Messaging Service](./milestone-5.md)

**Scope:** Message send/receive lifecycle — `MessageCodec` envelope, `RelayService` transport, repositories, sync, delivery status tracking.

> **Index correction (2026-09):** this entry previously described an "Attachments & Media Pipeline" milestone; the actual spec file (and proposal phase 5) is the Messaging Service. The full attachment download/upload pipeline is scoped out of every current milestone — it is unowned follow-up work.

---

### [Milestone 6: UI & Keyboard](./milestone-6.md)

**Scope:** User interface — conversation list, thread detail, `Lp3Keyboard` message input, attachment viewer, settings.

> **Index correction (2026-09):** this entry previously described a "Hardening & Production" milestone; the actual spec file (and proposal phase 6) is UI & Keyboard. No hardening spec exists in the codespec series; hardening tracks under proposal phases 7–8.

---

## Navigation

- **Back to Project:** [v1 Project Overview](../index.md)
- **Architecture Decisions:** [Architecture Decision Records](../../adrs/)
- **Project Status:** [Current Milestone & Progress](../index.md#current-milestone)

## Legend: Internal Stories (S1–S5)

Each milestone's **Implementation Timeline** Gantt chart includes **5 internal stories**:

- **S1–S4:** Logical phase gates for progress tracking within the milestone (not external milestones)
- **S5:** Milestone completion checkpoint (review, documentation, handoff)

These stories help visualize task grouping and phase dependencies. They are **not** the same as the larger project milestones (M2–M6); they are internal checkpoints specific to each milestone spec.
