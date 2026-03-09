# PineKone PK-UTP v1 App Architecture

## Layers
- **Protocol Core (`protocol`)**: Kotlin data classes and serializers that implement the PK-UTP v1 envelope, policy, hints, and fragment framing. Provides JSON/CBOR codecs plus validation helpers for advisory vs hard caps.
- **Engine (`engine`)**: Stateful orchestration that tracks device identity, peer sessions, envelope routing state, and score governors. Exposes `StateFlow` feeds for peers, envelopes, and custody events.
- **Transports (`transport`)**:
  - `NearbyMeshTransport`: Offline-first Bluetooth LE + Nearby Connections bridge for hop-to-hop forwarding and store/carry.
  - `WebMailboxTransport`: HTTP/3/WebTransport client reaching WMX endpoints when mesh is unavailable.
- **Storage (`store`)**: Room database for envelopes, peers, and scoring telemetry. Companion `ProtoDataStore` slot keeps user caps and identity secrets.
- **UI/ViewModel (`ui`)**: Home tabs render `Chats`, `Members`, `Nearby`. Invite/Join flows surface QR codes and scanning. ViewModels observe engine flows, maintain Compose-ready state, and handle user actions (send message, toggle relay caps, view diagnostics).

## Threading & Scope
- Engine hosted in `PkEngine`, started from `Application` via `lifecycleScope`.
- Transports run in their own coroutine scopes; back-pressure coordinated via `Channel`.
- Serialization happens off the main thread using `Dispatchers.IO`.

## Key Data Types
- `PkEnvelope`: faithful implementation of spec section 1.
- `PkPolicy`, `PkHints`, `PkOps`, `PkWebHints`, `PkFrag`, `PkAuth`.
- `PkControlFrame` sealed hierarchy (HACK, CLAIM, NACK).
- `PkPeer` (id, capability caps, quality metrics).
- `PkMessage` aggregator for UI-level chat threads.

## Interop
- JSON codec for debugging/dev tools.
- CBOR codec on the wire using `kotlinx-serialization-cbor`.
- Minimal TLV encoder/decoder for ultra-low stack fallback.

## Hard Cap Enforcement
- `CapGovernor` centralizes enforcement of battery, fanout, budget, payload size.
- Transports request forwarding tokens from governor before transmitting.

## Testing
- JVM tests cover envelope encoding/decoding round-trips, policy signature hashing, k-pipe math, and TLV encoding.
- Instrumented tests simulate mesh hops with fake transports to ensure TTL decrement and dedup.

## Outstanding Items
- BLE characteristic protocol for ultra-low hardware.
- PoW/postage token acquisition (stub for now, returns success).
- WMX WebTransport endpoint currently mocked behind interface.
