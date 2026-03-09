# PineKone Mesh

PineKone Mesh is a relational, delay-tolerant mesh messaging system built around scoped identities, governance lineage, opportunistic custody, and progressively condensing routing.

## Repository layout

- `docs/` contains the architecture, UI/UX probe, style lock, and unified specification.
- `PineKone/` contains the Android application and transport/runtime implementation.
- `tools/` contains local helper tooling.

## Canonical architecture

The main implementation reference is:

- `docs/pinekone_mesh_architecture_unified.md`

## Android app

The Android project lives in `PineKone/`.

Useful commands:

```bash
cd PineKone
./gradlew assembleDebug
```

## License

This repository is licensed under the GNU Affero General Public License v3.0 or later. See `LICENSE`.
