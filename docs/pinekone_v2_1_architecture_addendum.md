# PineKone v2.1 Architecture Addendum (Draft)

> Canonical reference: `docs/pinekone_mesh_architecture_unified.md` is the merged v2 + v2.1 implementation spec.


## 0) Status and Intent
- Status: Draft specification addendum to v2.
- Intent: Formalize PineKone as a self-mutating relational topology where forwarding decisions and graph mutation are part of the same state transition.
- Compatibility: v2.1 is additive and backward-compatible at the wire-contract level when optional fields are omitted.
- Product naming: Public product name is `PineKone Mesh`; canonical slug is `pinekone-mesh`.

## 1) Scope
This addendum extends the v2 architecture with:
- A formal state-transition model for relational routing.
- Progressive cryptographic condensation semantics.
- Mandatory hop-induced mutation semantics.
- Accountability enforcement decoupled from rotating routing aliases.

This addendum does not change v2 non-goals around universal anonymity against arbitrary global collusion.

## 2) Formal System Model
At time `t`, define network state as:

`G_t = (V, E_t, Sigma_t, AG)`

Where:
- `V`: physical nodes.
- `E_t`: active physical links at `t` (BLE/Wi-Fi Aware/Wi-Fi P2P/LAN/Nearby/Web).
- `Sigma_t`: relational state (aliases, role states, context bindings, epoch windows).
- `AG`: accountability graph (invite lineage, role attestations, revocations, decision receipts).

`AG` evolves through governance actions but is not identity-isomorphic to short-lived routing aliases.

## 3) Core Traversal Axiom
For an envelope `Env`, forwarding is a state transition:

`T_hop(Env, n_i -> n_j, G_t) => (decision, G_{t+1})`

Meaning:
- `Env` is not routed over a static graph.
- Each accepted hop MUST mutate at least one relational component in `Sigma_t`.
- Therefore, replaying observed hops against later topology snapshots is intentionally unstable.

## 4) Convergent Relational Routing
### 4.1 Destination Targeting
Origin targets a cryptographic relational context commitment `C_dest` (not a globally stable identity).

### 4.2 Relational Distance
Define `R_d(n, C_dest)` as local relational distance from node `n` to target context.
- High distance: node is effectively unrelated.
- Zero distance: node can fully resolve/decrypt destination scope.

### 4.3 Condensation Phases
1. Unrelated drift:
   Nodes unable to resolve context forward by local policy and transport availability.
2. Boundary crossing:
   A node with partial context material extracts deeper routing hints (order/community scope) and biases next-hop scoring.
3. Condensation:
   As distance lowers, routing entropy collapses and forwarding converges quickly toward the destination set.
4. Resolution:
   Destination context fully resolves and payload is consumable.

### 4.4 Routing Rule
`PathScorer` SHOULD prefer candidates expected to reduce `R_d`, subject to safety clamps (battery, storage, queue, policy).
If no such candidate exists, node MAY `STORE_CARRY` or escalate to custody per Section 5.

## 5) Hop-Induced Mutation Semantics
For each successful forward/evaluate cycle, node MUST apply one or more mutation classes:

### 5.1 Custody Mutation
If a node is closer to `C_dest` but no viable immediate hop exists:
- Transition role `RELAY_ONLY|CUSTODY_ELIGIBLE -> CUSTODY_ACTIVE` (when policy permits).
- Emit `CustodyReceiptV2` / `DecisionReceipt` with reason code.

### 5.2 Alias Dissolution and Rotation
After sensitive relay interaction windows:
- Burn/retire linkable alias pair material.
- Issue `ALIAS_ROTATE` control flow.
- Support bounded dual-alias grace only for liveness.

### 5.3 Edge Reweighting
Update local route weights after hop outcome:
- Increase trust/utility for successful low-cost progress.
- Decay stale or high-risk edges.

### 5.4 Hint Redaction Progression
As hop count and policy thresholds advance:
- Reduce exposed routing hints for nodes that fail relational checks.
- Reveal deeper hints only to nodes with qualifying context proofs.

## 6) Accountability in a Mutating Mesh
### 6.1 Identity Split
- Routing identity: rotating, scoped, short-lived alias.
- Accountability identity: stable cryptographic lineage root (`InviteAttestation` chain + role grants).

### 6.2 Abuse Handling
If node detects policy abuse (for example, fanout inflation, quota evasion, replay pattern):
- Drop or clamp envelope.
- Emit signed `DecisionReceipt` referencing observed alias evidence.
- Map evidence to lineage root through attestation proofs.
- Apply revocation/sever action at lineage level so future aliases derived from that root lose validity in scope.

### 6.3 Local-First Enforcement
Enforcement decisions are local and policy-scoped; global consensus is not required for immediate protective action.

## 7) Privacy and Observability Guarantees
v2.1 objective is strong path unlinkability under practical local observers through alias churn and per-hop mutation.

Normative boundary:
- v2.1 does NOT claim absolute anonymity against arbitrary global colluding adversaries.
- v2.1 DOES require that per-hop metadata exposure be minimized by distance policy and that route reconstruction reliability decays rapidly over time.

## 8) Protocol Deltas (Additive)
### 8.1 EnvelopeV2 optional extensions
- `ctx_commitment`: canonical commitment to destination relational context.
- `condense_depth`: current condensation stage or score.
- `mutation_nonce`: anti-linkability nonce tied to alias epoch.
- `hint_tier`: hint disclosure level for current hop window.

### 8.2 Control-frame extensions
- `ALIAS_ROTATE` (already planned in v2, elevated to required capability in v2.1 profiles).
- `LINEAGE_SEVER` (governance action referencing receipt/attestation evidence).
- `CONDENSE_PROOF` (optional proof artifact for partial-context routing claims).

### 8.3 Reason-code extensions
Add reason families for:
- `relational_unresolved`
- `condense_progress`
- `alias_rotated_post_hop`
- `lineage_sever_applied`

## 9) Processing Pipeline (Normative)
For each received envelope:
1. Validate envelope and policy signature/timestamps.
2. Clamp policy by local capability limits.
3. Evaluate relational distance/proofs against local bindings.
4. Select action: `FORWARD_NOW`, `STORE_CARRY`, `ACCEPT_CUSTODY`, `DROP_*`.
5. Emit decision artifact (receipt/reason) when required.
6. Apply mandatory mutation class(es) to `Sigma_t`.
7. Persist mutation and decision events atomically.

## 10) v2.1 Acceptance Criteria
1. Every successful hop produces at least one persisted relational mutation event.
2. Path scoring demonstrates monotonic bias toward lower `R_d` when candidate paths exist.
3. Alias-rotation behavior is observable and auditable without exposing stable global IDs.
4. Abuse enforcement can sever lineage validity independent of alias churn.
5. Mixed transports remain compatible with a single envelope model.
6. Existing v2 peers interoperate when v2.1 optional fields are absent.

## 11) Migration Notes from v2
- Reuse existing v2 objects (`EnvelopeV2`, `ControlFrameV2`, `InviteAttestation`, `DecisionReceipt`).
- Introduce v2.1 fields as optional with capability negotiation.
- Keep current custody and policy clamps unchanged; add mutation persistence and relational-distance signals incrementally.

## 12) Open Questions for Finalization
- Canonical computation of `R_d` across heterogeneous trust domains.
- Minimum safe alias grace window before full dissolution.
- Evidence thresholds for automatic `LINEAGE_SEVER` vs operator approval.
- Cost model for `CONDENSE_PROOF` on constrained devices.
