# PineKone v2 Pass 2 UI/UX Probe

## Goal
Evaluate the current Android UI/UX against the v2 architecture direction:
- relational/dynamic identity
- distance-bounded privacy with accountability
- governance in protocol
- opportunistic custody mailbox
- multi-medium routing transparency

## Audit Scope
Reviewed:
- Activities: `Auth`, `Home`, `Invite`, `Join`, `Chat`
- Fragments: `Contacts`, `PublicChat`, `Nearby`
- Adapters/ViewModel and layouts under `PineKone/app/src/main/java/com/pinekone/app` and `PineKone/app/src/main/res/layout`

## Current UX Snapshot
1. App shell is clean and simple:
- 3 tabs (`Private`, `Public`, `Nearby`)
- core send/receive flow works
- retry and ping feedback exists

2. Current mental model is contact-centric and transport-light:
- user sees contacts, message status, and limited transport readiness
- user does not see protocol governance, custody, or scoped identity semantics

3. UI is MVP-focused:
- minimal diagnostics
- no explicit risk/governance controls
- no advanced privacy/accountability controls

## Human Interface Broad Picture (v2)
Primary end-user surfaces:
1. `Direct` (private conversations)
2. `Broadcast` (public/community conversations)
3. `Peers` (radio-near nodes and link quality)

Secondary/advanced surfaces (progressive disclosure):
1. `Network` (governance graph, epochs, revocations, attestations)
2. `Custody` (mailbox queue, expiry, transfer/release traces)

Navigation intent:
- Most users should complete daily use from `Direct/Broadcast/Peers`.
- `Network/Custody` should appear only when needed:
  policy incident, delayed delivery, trust/governance action, or explicit power-user intent.

## Message State Model (end-user value)
Required states for payload lifecycle:
1. `Sent`:
- confirms app accepted/send attempt started.
2. `In transit`:
- confirms network is actively forwarding/store-carry, reducing duplicate re-sends.
3. `Delivered`:
- confirms recipient device reached.
4. `Read`:
- confirms recipient viewed message (policy/consent controlled).

Why users need these:
- they answer four distinct questions:
  `Did my tap work?` -> Sent
  `Is the network still working on it?` -> In transit
  `Did it reach the other device?` -> Delivered
  `Did the person actually see it?` -> Read

## Screen-Level Findings
### Auth
- Supports local PIN + biometric unlock.
- Missing: membership epoch awareness, device identity lifecycle cues, key rotation/recovery UX.

### Home + Tabs
- Good entry point; radio mode toggle exists (`Full mesh` vs `BT only`).
- Missing: no global status chips for governance epoch, custody mode, revocations, relay health class.

### Contacts / Chat
- Strong baseline for person-to-person messaging and resend on failure.
- Missing:
  - no route/custody timeline
  - no reason-code visibility (`policy_clamped_fanout`, `deadline_insufficient`, etc.)
  - no alias scope/order indication
  - no trust/delegation metadata

### Public Chat
- Functional broadcast thread.
- Missing:
  - no policy controls for public channel (scope, retention, accountability level)
  - no moderation/governance cues

### Nearby
- Best current diagnostics surface (peer list, ping, map, transport status).
- Missing:
  - no first/second/third-order relation visualization
  - no alias epoch, role attestation, or custody capability indicators
  - no per-hop path view
  - no custody activation/deactivation controls
  - no trust classification for nearby nodes
    (`related/trusted` vs `unrelated/unverified`)

### Invite / Join
- QR-based onboarding works.
- Missing:
  - no invite-chain attestation UI
  - no role grant preview (`relay`, `custody`)
  - no membership epoch/revocation warnings

## Gap Matrix (Current -> Required v2)
| Current UI element | v2 requirement | Gap | Priority | Proposed UX direction |
|---|---|---|---|---|
| Contact name + static thread list | Relational dynamic identities | No scoped aliases/order context | P0 | Add identity badge (L1/L2/L3+ scope), alias epoch tag, optional resolved display name |
| Message status (`Pending/Sent/Delivered/Failed`) | Explainable routing/custody decisions | No reason codes or custody phase | P0 | Add expandable message trace: forward/store/custody/ack events with reason labels |
| Retry snackbar only | Policy transparency | No policy clamp/deadline info | P0 | Add inline "Why delayed?" card with machine-readable reason text |
| Nearby peer meta (seen, battery, transport) | Governance + accountability | No role attestation/revocation state | P0 | Add peer role chips (`relay`, `custody`, `revoked`, `unverified`) |
| Nearby list/map | Transport-near vs trust-near distinction | Nearby implies trust today | P0 | Split labels: `Nearby Trusted`, `Nearby Unverified`; add safe action gating before trust actions |
| Invite QR plain payload | Governance-in-protocol | No invite-chain visibility | P0 | Invite details sheet with attestation chain summary and risk flags |
| Join flow save contact | Membership controls | No epoch compatibility/revocation check UX | P0 | Pre-join validation screen with compatibility + revocation indicators |
| No custody inbox/outbox views | Opportunistic mailbox UX | Custody lifecycle invisible | P0 | Add custody surface (tab or secondary screen): accepted items, expiry, transfer, release outcomes |
| Home top bar | Network-wide state | No governance/custody summary | P1 | Add status strip: epoch, active custody mode, revocation count, transport health |
| Public chat basic composer | Policy profile control | No per-room accountability/privacy mode | P1 | Add room policy selector and clear mode labels |
| Nearby map static pseudolocation | Graph order semantics | No order-aware graph visualization | P1 | Add relation graph mode with order rings and disclosure boundaries |
| Contact options rename/delete | Trust management | No delegation/revocation controls | P1 | Add trust actions: delegate role, revoke link, quarantine peer |
| Auth setup | Identity lifecycle | No key rotation/recovery UX | P2 | Add identity management section in settings |
| Theme/layout consistency | Pass 3 style lock readiness | No explicit design token system doc | P2 | Define typography/color/spacing/motion tokens and component variants |

## UX States Missing (Must Add Before Pass 4)
1. Identity and relation states:
- scoped alias
- order distance
- epoch rotation state
- attestation validity

2. Governance states:
- invite accepted/rejected
- role delegated/revoked
- member revoked/quarantined
- epoch mismatch

3. Custody states:
- custody offered/accepted/rejected
- expiry countdown
- transferred/released
- fetch token available/expired

4. Routing states:
- next-hop selected
- path changed after re-encounter
- store-carry defer
- no viable path

5. Privacy/accountability states:
- disclosure tier active
- accountability proof attached
- limited metadata mode active

## IA / Navigation Changes Needed
Recommended structure:
1. Keep top-level tabs focused on daily communication:
- `Private` -> `Direct`
- `Public` -> `Broadcast`
- `Nearby` -> `Peers`

2. Add advanced surfaces with progressive disclosure:
- `Network`: open from top-bar action, diagnostics sheet, or settings.
- `Custody`: open from message status ("In transit via custody") or top-bar action.

3. Use inline summaries instead of forcing tab switches:
- examples:
  `Delayed: in custody, expires in 2h`
  `Policy update: 1 peer revoked`
  with `View details` deep-link into advanced surfaces.

## Priority Backlog for Pass 4 (Frontend)
### P0 (must)
1. Message trace and reason-code UI
2. Scoped identity/order badges
3. Invite attestation + join validation UX
4. Custody lifecycle surface
5. Peer role/revocation indicators
6. Nearby trusted/unverified split
7. Message state model with `Sent/In transit/Delivered/Read`

### P1 (should)
1. Graph-order visualization mode
2. Governance event feed
3. Public channel policy mode UI
4. Trust/delegation actions in contact/peer menus

### P2 (later)
1. Identity rotation management center
2. Advanced accessibility tuning and dense diagnostics mode
3. Rich path replay visualization

## Pass 2 Exit Check
Pass 2 objective met:
- current UX has been audited
- v2-required UX deltas identified
- prioritized UI backlog defined for style lock (Pass 3) and frontend build (Pass 4)
