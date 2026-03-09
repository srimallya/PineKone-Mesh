# PineKone v2 Detailed Architecture

> Canonical reference: `docs/pinekone_mesh_architecture_unified.md` is the merged v2 + v2.1 implementation spec.


## 1) Purpose
PineKone v2 is a transport-agnostic, policy-driven delay-tolerant mesh protocol and application architecture.
Public product name: `PineKone Mesh` (canonical slug: `pinekone-mesh`).

Primary objective:
- Any node can communicate over any available medium (`BLE`, `Wi-Fi P2P`, `Wi-Fi Aware`, `LAN multicast`, `Nearby`, and `Web mailbox`).
- Any capable node can opportunistically become a temporary custody mailbox when direct path quality is poor.

Design statement:
- "One envelope and policy model across all transports, with local transport selection per hop."

## 2) Scope and Non-Goals
### In scope
- Unified envelope schema for private and public messages.
- Policy-in-packet forwarding and local capability clamping.
- Multi-transport hop-by-hop routing and bridge behavior (`bt2wifi`, `wifi2wifi`, `bt2web`, etc.).
- Custody mailbox semantics, receipts, fetch, expiry, and role transitions.
- Security model, observability model, rollout/migration path.

### Out of scope (v2 initial release)
- Global consensus/blockchain style coordination.
- Global, unconditional anonymity guarantees against arbitrary colluding observers.
- Full internet NAT traversal replacement for WebRTC (WebRTC remains valid for direct internet P2P).

## 3) Architecture Principles
1. Transport independence:
   The protocol core must not encode transport-specific assumptions.
2. Policy as contract:
   Sender intent is explicit; relay enforces local limits and returns reason codes.
3. DTN-first:
   Store-carry-forward is normal behavior, not an exception.
4. Opportunistic custody:
   Mailbox role is dynamic and driven by device/network conditions.
5. Safety over liveness:
   Device constraints (battery/queue/storage) always clamp sender requests.
6. Explainability:
   Every relay/custody decision should emit machine-readable reason codes.
7. Relational identity:
   Identities are scoped to relationships/orders and rotate with epoch.
8. Governance in protocol:
   Membership, delegation, revocation, and accountability receipts are first-class protocol state.

## 4) System Topology and Roles
### Node roles
- `RELAY_ONLY`: forwards data but does not store custody long-term.
- `CUSTODY_ELIGIBLE`: can accept custody if policy and local status allow.
- `CUSTODY_ACTIVE`: currently serving mailbox responsibilities.
- `CLIENT_ONLY`: originates/consumes messages; no relay.

### Path examples
- `BLE -> BLE`
- `BLE -> Wi-Fi Aware -> Wi-Fi P2P`
- `Wi-Fi P2P -> LAN -> BLE`
- `BLE -> LAN -> Custody Mailbox -> Web Client Fetch`

### Relational identity model
- No global directory of real identities is exposed by the protocol.
- Each node maintains local relationship-scoped aliases (dynamic pseudonyms).
- Identity visibility is distance-bounded and policy-driven:
  direct peers see local alias plus attestations needed for trust, while farther orders receive less-resolvable identifiers.
- Distance windows are configurable by governance profile (for example, stricter privacy outside local order radius).
- Accountability is preserved via signed attestations and receipts, without requiring globally stable user IDs.

## 5) Layered Architecture
### 5.1 Protocol Core
Responsibilities:
- Envelope and control-frame schemas.
- Serialization (`JSON`, `CBOR`, `TLV` fallback).
- Canonical hashing/signing representation.
- Validation and compatibility rules.

Key outputs:
- `EnvelopeV2`
- `PolicyV2`
- `NodeCapabilitiesV2`
- `ControlFrameV2`
- `CustodyReceiptV2`

### 5.2 Routing and Policy Engine
Responsibilities:
- Merge sender policy with local reality.
- Select action: forward, defer/store, custody-accept, custody-reject.
- Choose best outbound medium for next hop.

Core modules:
- `CapGovernorV2`
- `PathScorer`
- `RelayScheduler`
- `CustodyRoleManager`

### 5.3 Transport Adapters
Responsibilities:
- Discovery, session open, send/receive bytes.
- Report capabilities/quality stats.
- No protocol decisions beyond framing limits and availability.

Adapters:
- `BleGattAdapter`
- `WifiP2pAdapter`
- `WifiAwareAdapter`
- `LanMulticastAdapter`
- `NearbyAdapter`
- `WebMailboxAdapter`

### 5.4 Storage and State
Responsibilities:
- Local message journal.
- Peer capability snapshots.
- Custody store with TTL/expiry.
- Retry and decision event history.

Stores:
- `IdentityStore`
- `MessageStore`
- `CustodyStore`
- `PeerStore`
- `PolicyEventStore`

### 5.5 API and UX Layer
Responsibilities:
- Present transport/routing state.
- Surface delivery and custody status in user terms.
- Offer relay/custody controls and diagnostics.

## 6) Protocol Objects (v2 draft)
### 6.1 EnvelopeV2 (conceptual)
Core fields:
- `ver`: protocol version.
- `msg_id`: 16-byte message id.
- `trace_id`: optional correlation id for diagnostics.
- `alias_ctx`: scoped alias context (community/epoch/order scope).
- `ttl`: hop limit.
- `deadline_ms`: absolute or relative delivery deadline.
- `policy`: `PolicyV2`.
- `hints`: destination/community/routing hints.
- `ops`: operation flags (ack modes, store-carry permissions).
- `web`: mailbox/fallback hints.
- `frag`: fragment metadata.
- `auth`: signature/fingerprint/origin data.
- `payload`: encrypted bytes or public payload bytes.

### 6.2 PolicyV2
Required policy dimensions:
- Spread:
  `fanout_initial`, `fanout_decay`, `fanout_min`.
- Retry:
  `retry_limit`, `retry_backoff_class`.
- Resource:
  `min_batt_pct`, `max_tx_bytes`, `max_retry_cost`.
- Priority:
  `delivery_class`, `priority`.
- Fallback:
  `fallback_after_ms`, `custody_required`, `web_fallback_allowed`.
- Security:
  `policy_ver`, `policy_id`, `issued_at`, `expires_at`, `policy_sig`.

### 6.3 NodeCapabilitiesV2
Advertised by handshake:
- `node_alias`, `display_name`, `public_key`, `fingerprint`.
- `radios`: list of supported media.
- `max_payload_bytes_by_radio`.
- `battery_class`, `storage_class`.
- `custody_capable`, `custody_capacity_bytes`, `custody_uptime_hint`.
- `software_version`, `protocol_versions_supported`, `membership_epoch`.

### 6.4 ControlFrameV2
Planned control families:
- Delivery:
  `ACK`, `NACK`, `CLAIM`.
- Reachability:
  `PING`, `PONG`.
- Custody:
  `CUSTODY_OFFER`, `CUSTODY_ACCEPT`, `CUSTODY_REJECT`, `CUSTODY_RELEASE`.
- Diagnostics:
  decision reason and clamping reports.
- Governance:
  `INVITE_ATTEST`, `ROLE_ATTEST`, `REVOKE_MEMBER`, `REVOKE_ROLE`, `ALIAS_ROTATE`.

### 6.5 Governance and identity objects
- `InviteAttestation`:
  signed invite-chain object for admission and accountability lineage.
- `RoleAttestation`:
  scoped role grants (`relay`, `custody`, `admin`) with expiry and policy domain.
- `MembershipEpoch`:
  epoch marker used to phase out stale aliases/attestations.
- `AliasBinding`:
  local mapping from scoped alias to next-hop resolvable identity context.
- `DecisionReceipt`:
  signed relay/custody decision artifact carrying reason code and timestamp.

## 7) Message Lifecycle
1. Origin:
   Sender creates `EnvelopeV2` with policy and payload.
   Sender selects scoped destination alias, not global identity.
2. Local evaluation:
   `CapGovernorV2` clamps sender policy against local limits.
3. Next-hop selection:
   `PathScorer` chooses transport + peer.
   Path scorer uses local alias bindings and distance/privacy policy.
4. Relay:
   Node forwards or stores based on policy/deadline/state.
5. Custody transition (optional):
   Custody-capable node accepts and returns receipt.
6. Delivery:
   Recipient acknowledges according to `ack_mode`.
7. Completion:
   Message marked delivered, expired, or failed with reason.
8. Re-encounter reorganization:
   if two previously distant nodes meet directly, they create a new first-order edge, rotate aliases, and future routes prefer the new edge while old long-path linkage ages out.

## 8) Routing and Decision Model
### 8.1 Inputs
- Sender policy.
- Local battery, queue slack, link quality.
- Peer capability and historical reliability.
- Deadline/TTL remaining.
- Custody availability.

### 8.2 Decisions
- `FORWARD_NOW`
- `STORE_CARRY`
- `ACCEPT_CUSTODY`
- `REJECT_CUSTODY`
- `DROP_EXPIRED`
- `DROP_POLICY_VIOLATION`

### 8.3 Decision reason codes (examples)
- `battery_below_floor`
- `queue_saturated`
- `deadline_insufficient`
- `policy_clamped_fanout`
- `no_viable_path`
- `custody_capacity_exceeded`

### 8.4 Distance-bounded privacy and accountability
- Relay metadata exposure follows order-aware policy:
  nearby orders may expose richer accountability metadata; farther orders see reduced linkage.
- Forwarders know only local route context (previous/next hop), not full end-to-end path.
- Accountability graph is constructed from signed attestations and receipts, not from a public global identity table.

## 9) Opportunistic Mailbox (Custody) Architecture
### 9.1 Custody activation policy
A node enters `CUSTODY_ACTIVE` only if all are true:
- Battery above threshold.
- Free storage above threshold.
- Network reachability acceptable.
- User/admin policy allows custody mode.

Node exits custody mode on:
- Low battery.
- Storage pressure.
- Manual disable.
- Background policy restriction.

### 9.2 Custody contract
On custody acceptance, node returns:
- `receipt_id`
- `msg_id`
- `accepted_at`
- `expiry_at`
- `fetch_token` (optional)
- `proof`/signature (optional, recommended)

### 9.3 Fetch and release
- Clients can poll/push fetch by `receipt_id` or topic.
- Custody node can transfer custody to stronger node.
- Custody release emits terminal status and reason.

## 10) Security Model
### 10.1 Identity
- Per-node asymmetric key pair.
- Stable cryptographic root key for attestations.
- Relationship-scoped dynamic aliases derived per epoch/context.
- Signed capability, invite, role, and policy metadata where needed.

### 10.2 Payload security
- Private payloads are end-to-end encrypted.
- Public payloads are unsigned or signed depending on room policy.

### 10.3 Anti-abuse controls
- Clamp policy to local hard limits.
- Sender and peer quotas.
- Replay resistance (`nonce` + policy timestamps).
- Mailbox rate limiting and TTL enforcement.

### 10.4 Trust boundaries
- Relay can see envelope metadata unless protected by additional privacy mechanisms.
- Custody node is semi-trusted for availability, not plaintext.
- Invite-chain and governance material are shared on least-privilege basis, not globally broadcast.

## 11) Serialization and Compatibility
Codec strategy:
- `JSON`: debugging and observability.
- `CBOR`: production compact wire.
- `TLV`: constrained fallback.

Compatibility rules:
- Unknown fields ignored if explicitly marked optional.
- Required field absence is reject with reason.
- Version negotiation via handshake capabilities.

## 12) Data Model (Storage)
Recommended entities:
- `peers`
- `alias_bindings`
- `invite_attestations`
- `role_attestations`
- `revocations`
- `messages`
- `message_fragments`
- `relay_events`
- `custody_records`
- `custody_receipts`
- `transport_health_samples`

Critical indexes:
- `msg_id`
- `alias_ctx + alias_id`
- `membership_epoch`
- `attestation_id`
- `contact_id + timestamp`
- `receipt_id`
- `expiry_at`

## 13) Observability and Diagnostics
Required telemetry:
- Per-transport availability and error rates.
- Decision counters by reason code.
- Retry/backoff distributions.
- Custody acceptance/rejection and expiry stats.

Debug artifacts:
- Envelope trace timeline.
- Per-hop transport and delay information.
- Policy clamping report for each send.

## 14) Failure and Recovery Semantics
Failure classes:
- Link failure.
- Peer churn.
- Policy rejection.
- Expiry timeout.
- Custody failure.

Recovery actions:
- Retry on alternative medium.
- Store-carry defer with reevaluation schedule.
- Custody transfer attempt.
- Mark terminal failure with reason.

## 15) Implementation Phases (Aligned to Requested Plan)
### Pass 1: Architecture (current)
Deliverables:
- This v2 architecture document.
- Protocol object drafts and decision model.
- Acceptance criteria per subsystem.

Exit criteria:
- Team agreement on data contracts and responsibilities.

### Pass 2: UI/UX Probe
Activities:
- Audit current screens and flows against v2 operations.
- Identify missing states:
  transport mix, custody state, decision reason visibility, retry controls.
- Produce gap matrix:
  `current UI element -> required v2 behavior -> change required`.

Exit criteria:
- Approved UX backlog with priorities.

### Pass 3: UI Style Lock
Activities:
- Define typography, color tokens, spacing system, motion rules.
- Establish reusable component standards for mesh/custody telemetry.
- Produce annotated UI style guide.
Deliverable:
- `docs/pinekone_v2_ui_style_lock.md`

Exit criteria:
- Signed-off visual and interaction system.

### Pass 4: Frontend Implementation
Activities:
- Implement UI state machines and screens from style guide.
- Wire to mock v2 engine contracts first.
- Add instrumentation hooks for diagnostic states.

Exit criteria:
- UX-complete frontend running against mocked/stubbed protocol engine.

### Pass 5: Backend/Engine Implementation
Activities:
- Implement protocol core and routing/custody engine.
- Implement adapter unification and mailbox role transitions.
- Integrate persistence, telemetry, and controls.
- Conformance, integration, and soak tests.

Exit criteria:
- End-to-end v2 behavior validated across mixed transports and custody scenarios.

## 16) Acceptance Criteria (v2)
1. A single envelope schema works unchanged across all enabled transports.
2. Mixed-medium path relay works (`bt2wifi`, `wifi2wifi`, `bt2web`).
3. Policy clamping occurs and reason codes are persisted.
4. Custody mode can activate/deactivate based on local status.
5. Delivery outcomes are deterministic and explainable in logs/UI.
6. Backward-compatibility behavior is defined for unknown fields and older peers.
7. No global identity directory is required for routing or delivery.
8. Governance operations (invite, delegation, revocation, alias rotation) are representable and auditable in protocol frames.
9. Graph reorganization after new direct edges is observable and policy-compliant.

## 17) Risks and Mitigations
- Risk: duplicated routing semantics across protocol and transport.
  Mitigation: strict layer boundaries and adapter contracts.
- Risk: policy abuse (excessive fanout/retry).
  Mitigation: hard clamps + quotas + signatures.
- Risk: custody storage blow-up.
  Mitigation: strict TTLs, eviction policies, and capacity guards.
- Risk: UX complexity.
  Mitigation: progressive disclosure in diagnostics and clear default behavior.
- Risk: accountability metadata overexposure.
  Mitigation: scoped attestations, distance-aware disclosure, retention limits.
- Risk: alias churn causing routing instability.
  Mitigation: epoch windows, grace periods, and dual-alias transition support.

## 18) Immediate Next Steps
1. Convert section 6 (protocol objects) into concrete Kotlin data models (`EnvelopeV2`, `PolicyV2`, frames).
2. Define relational identity primitives (`AliasContext`, `AliasBinding`, epoch rotation rules).
3. Define governance frame schemas and validation (`InviteAttestation`, `RoleAttestation`, revocation flow).
4. Define machine-readable reason code enum and persistence schema.
5. Start Pass 2 UI/UX gap audit against existing app screens.
