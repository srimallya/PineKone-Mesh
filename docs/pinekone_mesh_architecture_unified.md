# PineKone Mesh Unified Architecture (v2 + v2.1)

## 0) Status and Naming
- Document role: Canonical architecture source of truth for Pass 4 and Pass 5.
- Product name: `PineKone Mesh`
- Canonical slug: `pinekone-mesh`
- Lineage: Merges `pinekone_v2_architecture.md` and `pinekone_v2_1_architecture_addendum.md`.

## 1) Purpose
PineKone Mesh is a transport-agnostic, policy-driven, delay-tolerant mesh protocol and application architecture.

Primary objective:
- Any node can communicate over any available medium (`BLE`, `Wi-Fi P2P`, `Wi-Fi Aware`, `LAN multicast`, `Nearby`, and `Web mailbox`).
- Any capable node can opportunistically become temporary custody when direct path quality is poor.
- Routing and relational state evolve together during forwarding.

Design statement:
- One envelope and policy model across all transports, with local transport selection per hop.
- Privacy is achieved through scoped identities, distance-bounded disclosure, and hop-induced relational mutation.

## 2) Scope and Non-Goals
### In scope
- Unified envelope schema for private and public messages.
- Policy-in-packet forwarding and local capability clamping.
- Multi-transport hop-by-hop routing and bridge behavior (`bt2wifi`, `wifi2wifi`, `bt2web`, etc.).
- Custody semantics, receipts, fetch, expiry, and role transitions.
- Relational routing with progressive cryptographic condensation.
- Governance and accountability (invite lineage, role grants, revocation, alias rotation, decision receipts).

### Out of scope (initial release)
- Global consensus/blockchain coordination.
- Unconditional anonymity guarantees against arbitrary colluding global observers.
- Full internet NAT traversal replacement for WebRTC.

## 3) Architecture Principles
1. Transport independence.
2. Policy as contract.
3. DTN-first behavior.
4. Opportunistic custody.
5. Safety over liveness.
6. Explainability by reason codes.
7. Relational identity with scoped rotating aliases.
8. Governance as first-class protocol state.
9. Hop-induced mutation as normal operation.

## 4) Formal System Model
At time `t`, network state is:

`G_t = (V, E_t, Sigma_t, AG)`

Where:
- `V`: physical nodes.
- `E_t`: active physical links.
- `Sigma_t`: relational state (aliases, role states, context bindings, epoch windows, local route weights).
- `AG`: accountability graph (invite lineage, role attestations, revocations, receipts).

`AG` is stable enough for accountability but decoupled from short-lived routing aliases.

## 5) Core Traversal Axiom
For envelope `Env`, each hop is a state transition:

`T_hop(Env, n_i -> n_j, G_t) => (decision, G_{t+1})`

Normative meaning:
- `Env` is not routed over a static graph.
- Each successful forward/evaluate cycle MUST mutate at least one component in `Sigma_t`.
- Route reconstruction reliability must decay as aliases rotate and relations reweight.

## 6) Topology, Roles, and Identity
### 6.1 Node roles
- `RELAY_ONLY`
- `CUSTODY_ELIGIBLE`
- `CUSTODY_ACTIVE`
- `CLIENT_ONLY`

### 6.2 Relational identity model
- No global directory of real identities.
- Relationship-scoped aliases rotate by epoch/context.
- Identity visibility is distance-bounded and policy-driven.
- Forwarders know local route context, not full end-to-end path.

### 6.3 Accountability split
- Routing identity: rotating scoped alias.
- Accountability identity: invite lineage and role attestations.

## 7) Convergent Relational Routing
### 7.1 Targeting
Sender targets a cryptographic relational context commitment `C_dest`, not a global user ID.

### 7.2 Relational distance
Define `R_d(n, C_dest)`:
- high `R_d`: unrelated
- low `R_d`: partially related
- `R_d = 0`: destination-resolvable

### 7.3 Condensation flow
1. Unrelated drift: unrelated nodes forward/store-carry with limited hints.
2. Boundary crossing: partially related nodes unlock deeper hints and bias path choice.
3. Condensation: route entropy decreases as `R_d` reduces.
4. Resolution: destination scope resolves and payload is consumable.

### 7.4 Path selection rule
`PathScorer` SHOULD prefer peers that reduce expected `R_d`, constrained by policy, battery, storage, queue, and deadlines.

## 8) Hop-Induced Mutation Semantics (Normative)
For each successful hop, node MUST apply one or more mutation classes:

1. Custody mutation:
- promote role to `CUSTODY_ACTIVE` when closer to context and no viable next hop exists.
- emit `CustodyReceiptV2`/`DecisionReceipt`.

2. Alias dissolution/rotation:
- retire linkable aliases after interaction windows.
- issue `ALIAS_ROTATE` with bounded grace for liveness.

3. Edge reweighting:
- strengthen successful low-cost progress edges.
- decay stale/risky edges.

4. Hint redaction progression:
- minimize disclosures for unresolved nodes.
- reveal deeper hints only with qualifying proofs.

## 9) Layered Architecture
### 9.1 Protocol Core
- Envelope/control schemas.
- Serialization (`JSON`, `CBOR`, `TLV` fallback).
- Canonical signing/hashing and compatibility rules.

### 9.2 Routing and Policy Engine
- Policy clamp and decision selection.
- Path scoring by relational distance and local constraints.
- Custody role management and relay scheduling.

### 9.3 Transport Adapters
- Discovery/session/send/receive bytes.
- Capability and quality reporting.
- No policy/routing semantics beyond framing and availability.

### 9.4 Storage and State
- Message journal, custody store, peer snapshots.
- Alias bindings and governance evidence.
- Decision and mutation event history.

### 9.5 API and UX Layer
- Explain delivery/custody/governance states.
- Surface reason codes and progressive diagnostics.

## 10) Protocol Objects
### 10.1 `EnvelopeV2`
Core:
- `ver`, `msg_id`, `trace_id`, `alias_ctx`, `ttl`, `deadline_ms`, `policy`, `hints`, `ops`, `web`, `frag`, `auth`, `payload`

v2.1 optional extensions:
- `ctx_commitment`
- `condense_depth`
- `mutation_nonce`
- `hint_tier`

### 10.2 `PolicyV2`
- spread, retry, resource, priority, fallback, security signature/timestamps.

### 10.3 `NodeCapabilitiesV2`
- node alias/key/fingerprint, radios, size limits, battery/storage classes, custody capacity hints, software/protocol versions, membership epoch.

### 10.4 `ControlFrameV2`
- delivery (`ACK`, `NACK`, `CLAIM`)
- reachability (`PING`, `PONG`)
- custody (`CUSTODY_OFFER`, `CUSTODY_ACCEPT`, `CUSTODY_REJECT`, `CUSTODY_RELEASE`)
- diagnostics (clamp/decision reports)
- governance (`INVITE_ATTEST`, `ROLE_ATTEST`, `REVOKE_MEMBER`, `REVOKE_ROLE`, `ALIAS_ROTATE`)

v2.1 extensions:
- `LINEAGE_SEVER`
- `CONDENSE_PROOF` (optional)

### 10.5 Governance and accountability objects
- `InviteAttestation`
- `RoleAttestation`
- `MembershipEpoch`
- `AliasBinding`
- `DecisionReceipt`

## 11) Message Lifecycle
1. Origin: create `EnvelopeV2` with scoped destination context.
2. Local evaluation: clamp policy by local limits.
3. Relational evaluation: estimate `R_d`, evaluate proofs/bindings.
4. Decision: `FORWARD_NOW` / `STORE_CARRY` / `ACCEPT_CUSTODY` / reject/drop.
5. Transit: send over selected medium.
6. Mutation: apply mandatory mutation class(es) after hop outcome.
7. Receipts: emit custody/decision artifacts as needed.
8. Completion: delivered/read/expired/failed with reason.
9. Re-encounter: reorganize preferred graph edges when new direct links form.

## 12) Decision Model and Reason Codes
### 12.1 Decisions
- `FORWARD_NOW`
- `STORE_CARRY`
- `ACCEPT_CUSTODY`
- `REJECT_CUSTODY`
- `DROP_EXPIRED`
- `DROP_POLICY_VIOLATION`

### 12.2 Reason codes (non-exhaustive)
- `battery_below_floor`
- `queue_saturated`
- `deadline_insufficient`
- `policy_clamped_fanout`
- `no_viable_path`
- `custody_capacity_exceeded`
- `relational_unresolved`
- `condense_progress`
- `alias_rotated_post_hop`
- `lineage_sever_applied`

## 13) Custody Architecture
### 13.1 Activation
Enter `CUSTODY_ACTIVE` only when battery, storage, reachability, and policy thresholds are satisfied.

### 13.2 Contract
On acceptance return receipt artifacts (`receipt_id`, `msg_id`, `accepted_at`, `expiry_at`, optional fetch token and proof).

### 13.3 Fetch/transfer/release
- fetch by receipt/topic.
- custody transfer to stronger node allowed.
- release emits terminal status and reason.

## 14) Security and Trust Boundaries
### 14.1 Identity and keys
- per-node asymmetric keys.
- stable cryptographic root for lineage attestations.
- dynamic relationship-scoped aliases.

### 14.2 Payload security
- private payloads are end-to-end encrypted.
- public payload signing depends on room policy.

### 14.3 Anti-abuse and enforcement
- hard policy clamps, quotas, replay resistance, TTL/rate controls.
- abuse evidence through signed receipts.
- enforcement can sever lineage validity independent of alias churn.

### 14.4 Privacy boundary
- objective: strong local path unlinkability with rapid decay in route reconstruction reliability.
- no absolute anonymity claim against arbitrary global collusion.

## 15) Serialization and Compatibility
- `JSON` for diagnostics, `CBOR` for production compactness, `TLV` fallback.
- unknown optional fields ignored.
- missing required fields rejected with reason.
- capability negotiation gates v2.1 optional behavior.

## 16) Data Model
Entities:
- `peers`, `alias_bindings`, `invite_attestations`, `role_attestations`, `revocations`, `messages`, `message_fragments`, `relay_events`, `mutation_events`, `custody_records`, `custody_receipts`, `transport_health_samples`

Critical indexes:
- `msg_id`, `alias_ctx + alias_id`, `membership_epoch`, `attestation_id`, `receipt_id`, `expiry_at`, `timestamp`

## 17) Observability
Required telemetry:
- per-transport availability/error rates
- decision counters by reason
- retry/backoff distributions
- custody acceptance/rejection/expiry
- mutation event rates (alias rotate, role shift, edge reweight)

Debug artifacts:
- envelope trace timeline
- per-hop medium/delay snapshots
- policy clamp reports

## 18) Failure and Recovery
Failure classes:
- link failure, peer churn, policy rejection, expiry timeout, custody failure

Recovery actions:
- retry alternate medium
- store-carry defer with reevaluation
- custody transfer
- terminal failure marking with reason

## 19) Implementation Phases
### Pass 1: Architecture
- Completed.

### Pass 2: UI/UX Probe
- Completed.

### Pass 3: UI Style Lock
- Deliverable: `docs/pinekone_v2_ui_style_lock.md`
- Completed.

### Pass 4: Frontend Implementation
- implement UI state machines and screens from style lock.
- wire to mocked v2/v2.1 contracts first.
- add instrumentation hooks.

### Pass 5: Backend/Engine Implementation
- implement protocol core, routing/condensation logic, custody transitions, adapter unification, persistence, telemetry, and conformance tests.

## 20) Unified Acceptance Criteria
1. One envelope schema works across enabled transports.
2. Mixed-medium relay works (`bt2wifi`, `wifi2wifi`, `bt2web`).
3. Policy clamping persists reason codes.
4. Custody mode activates/deactivates based on local status.
5. Delivery outcomes are deterministic and explainable.
6. No global identity directory required.
7. Governance operations are representable and auditable.
8. Graph reorganization from new direct edges is observable.
9. Every successful hop persists at least one mutation event.
10. Path scoring shows bias toward lower `R_d` when viable.
11. Abuse enforcement can sever lineage validity across alias churn.
12. v2 peers interoperate when v2.1 optional fields are absent.

## 21) Risks and Mitigations
- duplicated semantics across layers -> strict adapter contracts.
- policy abuse -> hard clamps + quotas + signatures.
- custody storage blow-up -> TTL + eviction + capacity guards.
- UX complexity -> progressive disclosure and clear defaults.
- accountability overexposure -> scoped attestations + retention bounds.
- alias churn instability -> epoch windows + grace transitions.
- over-costly condensation proofs -> optional proofs and device-class gating.

## 22) Immediate Next Steps
1. Treat this file as canonical architecture reference in implementation tickets.
2. Define Kotlin data models for unified protocol objects and v2.1 optional fields.
3. Build reason code enum and mutation event persistence schema.
4. Implement Pass 4 frontend using the locked style guide.
5. Implement backend engine and conformance test matrix.
