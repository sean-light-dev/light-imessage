# Initiative v1: LightOS iMessage Tool

**Status:** In Progress

**Current Milestone**: M3 (APNS<->UnifiedPush Bridge) — Kotlin side migrated into the Light SDK tool sandbox; native-service packaging pending

**Active ADR:** n/a

> Valid statuses: Draft → Proposed → Accepted → In Progress → Completed

> **Migration note (2026-09):** The implementation migrated from the standalone `light-message` app to the `light-imessage` SDK tool (`com.thelightphone.lightimessage`). See [migration-status.md](./migration-status.md) for the post-migration drift audit: which spec components are implemented as-written, which were adapted to the SDK sandbox, and which are pending.

---

## Index

| Document                              | Purpose                                                            |
| ------------------------------------- | ------------------------------------------------------------------ |
| [proposal.md](./proposal.md)          | Project scope, tech stack, milestones, timeline                    |
| [design.md](./design.md)              | Contracts, State Machines, Schemas                                 |
| [rustpush.md](./rustpush.md)          | Supplemental investigation: Rustpush+UnifiedPush Bridge            |
| [migration-status.md](./migration-status.md) | Post-migration drift audit and component status register    |
