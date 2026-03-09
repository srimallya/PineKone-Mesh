#!/usr/bin/env python3
"""
Standalone harness that mimics PineKone mesh packets and validates that encrypted
messages can travel between in-memory nodes. Supports optional multi-hop routing,
drop injection, and TTL exhaustion scenarios so you can exercise the envelope
flow outside of Android.

Usage:
    source .venv/bin/activate
    python tools/test_mesh_message.py --message "Hello from Alice" --rounds 2
    python tools/test_mesh_message.py --peers 4 --hops 2 --drop-prob 0.3 --ttl 5 --max-retries 2
    python tools/test_mesh_message.py --peers 64 --hops 62 --ttl 120 --rounds 5 --drop-prob 0.1 --seed 42
"""

from __future__ import annotations

import argparse
import json
import random
import secrets
import time
from dataclasses import dataclass

from nacl.public import Box, PrivateKey, PublicKey
from nacl.utils import random as sodium_random


def to_hex(data: bytes) -> str:
    return data.hex()


def from_hex(value: str) -> bytes:
    return bytes.fromhex(value) if value else b""


@dataclass
class PeerInfo:
    node_id: str
    display_name: str
    public_key: PublicKey
    fingerprint: bytes


class MeshNode:
    def __init__(self, display_name: str):
        self.display_name = display_name
        self._sk = PrivateKey.generate()
        self._pk: PublicKey = self._sk.public_key
        self.fingerprint = bytes(self._pk)[:8]
        self.node_id = to_hex(self.fingerprint)
        self.mesh: InMemoryMeshNetwork | None = None
        self.peers: dict[str, PeerInfo] = {}

    def attach(self, mesh: "InMemoryMeshNetwork") -> None:
        self.mesh = mesh

    def handshake_packet(self) -> dict:
        return {
            "type": "handshake",
            "nodeId": self.node_id,
            "displayName": self.display_name,
            "publicKey": to_hex(bytes(self._pk)),
            "fingerprint": to_hex(self.fingerprint),
            "capabilities": {"maxFanout": 2, "minBatteryPct": 15},
        }

    def ingest_handshake(self, packet: dict) -> None:
        peer_id = packet["nodeId"]
        if peer_id == self.node_id:
            return
        info = PeerInfo(
            node_id=peer_id,
            display_name=packet["displayName"],
            public_key=PublicKey(from_hex(packet["publicKey"])),
            fingerprint=from_hex(packet["fingerprint"]),
        )
        self.peers[peer_id] = info

    def send_message(
        self,
        contact_id: str,
        message: str,
        ttl: int,
        route: list[str] | None = None,
    ) -> dict:
        if self.mesh is None:
            raise RuntimeError("node is not attached to a mesh")
        if contact_id not in self.peers:
            raise KeyError(f"unknown contact {contact_id}")

        peer = self.peers[contact_id]
        msg_id = secrets.token_bytes(16)
        nonce = sodium_random(Box.NONCE_SIZE)
        box = Box(self._sk, peer.public_key)
        payload = box.encrypt(message.encode("utf-8"), nonce)

        envelope = {
            "ver": 1,
            "msg_id": to_hex(msg_id),
            "ttl": ttl,
            "policy": {
                "max_fanout": 2,
                "k_pipe": 20,
                "retry_limit": 3,
                "min_batt_pct": 15,
                "J_threshold": 5,
                "weights": {
                    "novelty": 255,
                    "coverage": 204,
                    "quality": 102,
                    "cost_latency": 255,
                    "cost_battery": 170,
                    "cost_dup": 85,
                    "cost_slack": 255,
                },
            },
            "hints": {
                "community_id": 0x3047,
                "target_hash": to_hex(peer.fingerprint),
                "priority": 1,
            },
            "ops": {"store_carry": True, "require_ack": True, "e2e_ack_path": True},
            "web": None,
            "frag": {"kind": "DATA", "seq": 0, "total": 1},
            "auth": {"origin_pk_fp": to_hex(self.fingerprint)},
            "payload": to_hex(bytes(payload)),
        }

        packet = {"type": "envelope", "envelope": envelope}
        path = [self.node_id]
        if route:
            path.extend(route)
        path.append(contact_id)
        delivered = self.mesh.route(path, packet)
        if not delivered:
            raise RuntimeError(f"message from {self.display_name} failed en route to {contact_id}")
        return packet

    def receive_packet(self, sender_id: str, packet: dict) -> str:
        if packet.get("type") != "envelope":
            raise ValueError(f"unsupported packet type {packet.get('type')}")
        envelope = packet["envelope"]
        peer = self.peers.get(sender_id)
        if peer is None:
            raise KeyError(f"no peer info for {sender_id}")

        box = Box(self._sk, peer.public_key)
        plaintext = box.decrypt(from_hex(envelope["payload"]))
        message = plaintext.decode("utf-8")

        print(
            f"[{self.display_name}] received message from {peer.display_name}: {message}"
        )
        return message


class InMemoryMeshNetwork:
    def __init__(self, drop_probability: float = 0.0, seed: int | None = None):
        self.nodes: dict[str, MeshNode] = {}
        self.drop_probability = drop_probability
        self._rng = random.Random(seed)
        self.drop_events = 0
        self.ttl_exhaustions = 0

    def register(self, node: MeshNode) -> None:
        node.attach(self)
        for existing in self.nodes.values():
            existing.ingest_handshake(node.handshake_packet())
            node.ingest_handshake(existing.handshake_packet())
        self.nodes[node.node_id] = node

    def route(self, path: list[str], packet: dict) -> bool:
        if len(path) < 2:
            raise ValueError("route requires at least sender and recipient")
        hop_packet = json.loads(json.dumps(packet))
        final_recipient = path[-1]
        origin = path[0]
        for index, (src, dst) in enumerate(zip(path, path[1:])):
            envelope = hop_packet["envelope"]
            ttl = envelope["ttl"]
            if ttl <= 0:
                self.ttl_exhaustions += 1
                print(f"[mesh] TTL exhausted before hop {src} -> {dst}")
                return False
            envelope["ttl"] = ttl - 1
            if self.drop_probability and self._rng.random() < self.drop_probability:
                self.drop_events += 1
                print(f"[mesh] dropped packet on hop {src} -> {dst}")
                return False
            if dst == final_recipient:
                print(f"[mesh] final hop {src} -> {dst} (TTL now {envelope['ttl']})")
                self.deliver(origin, dst, hop_packet)
            else:
                print(f"[mesh] forwarding hop {src} -> {dst} (TTL now {envelope['ttl']})")
        return True

    def deliver(self, sender_id: str, recipient_id: str, packet: dict) -> None:
        recipient = self.nodes.get(recipient_id)
        if recipient is None:
            raise KeyError(f"recipient {recipient_id} not found")
        recipient.receive_packet(sender_id, packet)


def main() -> None:
    parser = argparse.ArgumentParser(description="Test PineKone message envelope flow.")
    parser.add_argument("--message", default="ping from Alice")
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--json-dump", action="store_true", help="print last envelope JSON")
    parser.add_argument("--peers", type=int, default=2, help="number of mesh nodes to simulate (>=2)")
    parser.add_argument("--drop-prob", type=float, default=0.0, help="probability [0,1] that a hop drops the packet")
    parser.add_argument("--hops", type=int, default=0, help="number of intermediate relays between sender and recipient")
    parser.add_argument("--ttl", type=int, default=10, help="initial TTL applied to envelopes")
    parser.add_argument("--max-retries", type=int, default=0, help="retries per direction when delivery fails")
    parser.add_argument("--seed", type=int, help="seed for reproducible drop patterns")
    args = parser.parse_args()

    if args.peers < 2:
        raise SystemExit("need at least two peers to exchange messages")
    mesh = InMemoryMeshNetwork(drop_probability=args.drop_prob, seed=args.seed)
    nodes = [MeshNode(f"Node{i + 1}") for i in range(args.peers)]
    for node in nodes:
        mesh.register(node)

    successes = 0
    failures = 0
    last_packet = None
    for idx in range(args.rounds):
        sender = nodes[0]
        recipient = nodes[-1]
        route_nodes = []
        if args.hops > 0:
            if args.hops >= len(nodes) - 1:
                raise SystemExit("--hops must be less than number of peers - 1")
            route_nodes = [node.node_id for node in nodes[1 : 1 + args.hops]]

        payload = f"{args.message} #{idx + 1}"
        print(f"[{sender.display_name}] sending: {payload}")
        forward_ok = False
        for attempt in range(args.max_retries + 1):
            try:
                last_packet = sender.send_message(
                    recipient.node_id,
                    payload,
                    ttl=args.ttl,
                    route=route_nodes,
                )
                forward_ok = True
                if attempt > 0:
                    print(f"[mesh] forward succeeded after {attempt} retry(s)")
                break
            except RuntimeError as err:
                print(f"[mesh] forward attempt {attempt + 1} failed: {err}")
        if not forward_ok:
            failures += 1
            continue

        ack = f"ack {idx + 1} at {int(time.time())}"
        print(f"[{recipient.display_name}] replying with: {ack}")
        reverse_route = list(reversed(route_nodes))
        ack_ok = False
        for attempt in range(args.max_retries + 1):
            try:
                last_packet = recipient.send_message(
                    sender.node_id,
                    ack,
                    ttl=args.ttl,
                    route=reverse_route,
                )
                ack_ok = True
                if attempt > 0:
                    print(f"[mesh] ack succeeded after {attempt} retry(s)")
                break
            except RuntimeError as err:
                print(f"[mesh] ack attempt {attempt + 1} failed: {err}")
        if ack_ok:
            successes += 1
        else:
            failures += 1

    if args.json_dump and last_packet:
        print(json.dumps(last_packet, indent=2))

    total = successes + failures
    if total:
        print(
            f"\nSummary: {successes} success, {failures} failure over {total} round(s); "
            f"drops={mesh.drop_events}, ttl_exhaustions={mesh.ttl_exhaustions}"
        )


if __name__ == "__main__":
    main()
