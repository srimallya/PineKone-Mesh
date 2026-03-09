# PineKone Mesh v2 Pass 3 UI Style Lock

## 0) Status
- Pass: 3 (UI Style Lock)
- Product name: `PineKone Mesh`
- Canonical slug: `pinekone-mesh`
- Scope: Android app visual and interaction system for Pass 4 frontend implementation.

## 1) Visual Direction
Theme name: **Field Console**

Intent:
- Communication-first at a glance.
- Explicit trust/accountability states without fear-heavy visuals.
- "Concentric privacy circles" represented through distance bands and disclosure tiers.
- Diagnostics are progressive: calm by default, deep when expanded.

Tone:
- Operational, precise, and legible under stress.
- Avoid playful social styling.
- Avoid neon/cyber aesthetics.

## 2) UX Principles (Locked)
1. Proximity is transport, not trust.
2. Relation distance controls disclosure.
3. Accountability is visible, identity is scoped.
4. Every delay state must explain itself.
5. Daily flows stay simple; advanced surfaces are opt-in.

## 3) Information Architecture (Locked)
Primary tabs:
1. `Direct`
2. `Broadcast`
3. `Peers`

Secondary surfaces:
1. `Network` (governance, epochs, revocations, attestations)
2. `Custody` (mailbox lifecycle and receipts)

Entry rules:
- `Network` opens from top status strip, diagnostics links, or settings.
- `Custody` opens from message state links (for example "In transit via custody") or top status strip.

## 4) Design Tokens (Locked)
## 4.1 Color palette
Base:
- `bg.canvas`: `#F4F6F3`
- `bg.surface`: `#FFFFFF`
- `bg.elevated`: `#EEF2EC`
- `ink.primary`: `#10201A`
- `ink.secondary`: `#3B4B44`
- `ink.muted`: `#64756C`
- `stroke.default`: `#C8D2CB`

Brand/function:
- `brand.pine`: `#1F6B4A`
- `brand.moss`: `#2D8A63`
- `accent.cyan`: `#1C8CA8`

State:
- `state.ok`: `#227A4A`
- `state.info`: `#186E8A`
- `state.warn`: `#A06A12`
- `state.risk`: `#B34032`
- `state.revoked`: `#7C1F18`

Relation bands:
- `relation.l1`: `#2B7A53`
- `relation.l2`: `#3E8D79`
- `relation.l3`: `#6A7D86`
- `relation.unrelated`: `#8B9297`

Disclosure tiers:
- `tier.rich`: `#1E7F6B`
- `tier.limited`: `#A06A12`
- `tier.minimal`: `#68757D`

Contrast:
- Minimum 4.5:1 for body text.
- Minimum 3:1 for large text and icon-only controls.

## 4.2 Typography
Primary UI typeface:
- `Space Grotesk` (variable, bundled)

Monospace/diagnostics:
- `IBM Plex Mono` (bundled)

Type scale:
- `display`: 30/36, weight 600
- `title`: 22/28, weight 600
- `h1`: 18/24, weight 600
- `h2`: 16/22, weight 600
- `body`: 15/22, weight 450
- `body.small`: 13/18, weight 450
- `label`: 12/16, weight 600
- `mono.small`: 12/16, weight 500

Rules:
- Never use all-caps for long labels.
- Use monospace only for IDs, receipts, reason codes, and epochs.

## 4.3 Spacing and layout
Grid:
- 4dp base unit.
- Screen gutters: 16dp (phone), 24dp (tablet).

Spacing scale:
- `xs` 4dp
- `sm` 8dp
- `md` 12dp
- `lg` 16dp
- `xl` 24dp
- `2xl` 32dp

Corner radii:
- Cards: 14dp
- Pills/chips: 999dp
- Inputs/buttons: 12dp

Elevation:
- `e0` flat
- `e1` subtle card
- `e2` floating panel
- Keep shadows soft and short; use border contrast first.

## 4.4 Iconography
- Style: rounded-technical (24dp default).
- Stroke emphasis over fill for diagnostic clarity.
- Relation and trust indicators use shape + color, never color only.

## 5) Motion System (Locked)
Durations:
- `fast` 120ms (tap feedback, chip state)
- `base` 180ms (card expand/collapse)
- `slow` 260ms (screen transition)

Easing:
- Standard: `cubic-bezier(0.2, 0.0, 0.0, 1.0)`
- Exit: `cubic-bezier(0.4, 0.0, 1.0, 1.0)`

Patterns:
1. Staggered reveal for diagnostics lists (20ms step, max 6 items).
2. Message trace expansion as vertical timeline grow/fade.
3. Status strip transitions by crossfade + slide 8dp.

Rules:
- No perpetual animation.
- Motion must communicate state change, never decoration.

## 6) Component Standards (Locked)
## 6.1 Global status strip
Content:
- Epoch tag
- Custody mode (`OFF`, `ELIGIBLE`, `ACTIVE`)
- Revocation count
- Transport health class

Behavior:
- Always visible on primary tabs.
- Tap opens `Network` sheet.

## 6.2 Identity badge
Structure:
- Relation ring badge (`L1`, `L2`, `L3+`, `U`)
- Alias epoch tag
- Optional verified marker

States:
- `trusted`, `unverified`, `revoked`, `quarantined`

## 6.3 Message row and status rail
Required visible states:
- `Sent`
- `In transit`
- `Delivered`
- `Read`
- `Failed`

Expandable trace includes:
- forward/store/custody/ack events
- reason code chips
- timestamp and medium

## 6.4 "Why delayed?" card
Trigger:
- any non-terminal state longer than policy threshold

Must show:
- primary reason code
- current action (`store-carry`, `custody wait`, `retry backoff`)
- next reevaluation time
- deep-link to `Custody` or `Network`

## 6.5 Peer card (`Peers` tab)
Required fields:
- scoped alias
- relation band
- trust class
- active media chips
- role chips (`relay`, `custody`, `revoked`)
- last seen + health score

## 6.6 Custody record row
Required fields:
- `msg_id` short hash
- state (`offered`, `accepted`, `rejected`, `transferred`, `released`, `expired`)
- expiry countdown
- receipt quick view

## 6.7 Governance event row (`Network`)
Required fields:
- event type (`invite`, `role grant`, `revoke`, `alias rotate`, `lineage sever`)
- actor scope (never global identity unless locally resolved)
- timestamp + attestation reference

## 7) Screen Blueprints (Locked)
## 7.1 Direct thread
- Keep familiar chat layout.
- Add inline state rail per outgoing message.
- Add expandable diagnostic section per message.

## 7.2 Broadcast
- Composer includes room policy mode chip.
- Message cards show accountability tier indicator.

## 7.3 Peers
- Two sections: `Nearby Trusted` and `Nearby Unverified`.
- Optional relation graph view toggle with order rings.

## 7.4 Network
- Event feed first.
- Secondary tabs: `Attestations`, `Revocations`, `Epoch`.

## 7.5 Custody
- Three segments: `Active`, `Pending`, `Released/Expired`.
- Each item deep-links back to source message trace.

## 8) Content and Copy Rules
- Use plain language for user-facing states.
- Pair each human label with machine reason in details view.
- Avoid terms like "anonymous" or "untraceable" in UI claims.
- Preferred labels:
  - "Limited disclosure"
  - "Scoped identity"
  - "Accountability proof available"

## 9) Accessibility and Ergonomics
- Minimum touch target: 44dp.
- Support dynamic type up to 130% without clipping.
- Color-blind safety: every state must include icon/shape/text.
- Keep critical status text out of color-only chips.

## 10) Responsive Behavior
Phone:
- Single-column flow.
- Diagnostics in bottom sheets.

Tablet/foldable:
- Two-pane for `Direct`, `Peers`, and `Custody`.
- Persist `Network` event feed in side pane where width permits.

## 11) Implementation Contract for Pass 4
Deliverables required from frontend:
1. Centralized token file (`color`, `type`, `spacing`, `motion`, `shape`).
2. Reusable components for status strip, identity badge, trace timeline, custody row, governance row.
3. Preview catalog showing all required states (including empty/error/loading).
4. Feature flags for advanced surfaces (`Network`, `Custody`) during phased rollout.

## 12) Pass 3 Exit Criteria (Signed-Off Checklist)
1. Token values frozen and documented.
2. Component variants and required states frozen.
3. Motion rules frozen and prototyped.
4. Accessibility checks passed on locked components.
5. Blueprint coverage complete for `Direct/Broadcast/Peers/Network/Custody`.
6. Pass 4 implementation contract accepted by frontend owners.

