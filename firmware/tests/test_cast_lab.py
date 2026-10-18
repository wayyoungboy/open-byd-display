"""Host-lab cast control ops for the self-developed protocol."""

from __future__ import annotations

import json
import socket
import tempfile
import threading
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from openbyddisplay.service import Laboratory, Server


def _exchange(port: int, body: dict) -> dict:
    with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
        sock.sendall((json.dumps(body) + "\n").encode())
        line = sock.makefile("rb").readline()
    return json.loads(line.decode())


class CastLabTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        key = Ed25519PrivateKey.generate()
        pub = root / "pub.pem"
        pub.write_bytes(
            key.public_key().public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        )
        self.lab = Laboratory(root / "state", key.public_key(), "song-plus-dmi-2021", "host-lab")
        self.server = Server(0, self.lab)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.tmp.cleanup()

    def test_cast_lifecycle_and_overlays(self) -> None:
        status = _exchange(self.port, {"op": "cast.status"})
        self.assertEqual("idle", status["state"])
        profiles = _exchange(self.port, {"op": "cast.profile.get"})
        self.assertEqual(3, len(profiles["profiles"]))
        started = _exchange(
            self.port,
            {
                "op": "cast.start",
                "request_id": "t1",
                "epoch": status["epoch"],
                "expected_revision": 0,
                "profile_id": 1,
            },
        )
        self.assertTrue(started["ok"])
        self.assertEqual("playing", started["state"])
        beat = _exchange(
            self.port,
            {"op": "cast.heartbeat", "epoch": started["epoch"], "revision": started["revision"]},
        )
        self.assertTrue(beat["ok"])
        music = _exchange(self.port, {"op": "music.show", "music": {"title": "x"}})
        self.assertTrue(music.get("overlay"))
        stopped = _exchange(self.port, {"op": "cast.stop", "request_id": "t2"})
        self.assertEqual("idle", stopped["state"])
        self.assertFalse(stopped["target_casting"])

    def test_rejects_commercial_auth_fields(self) -> None:
        reply = _exchange(self.port, {"op": "cast.status", "token": "abc"})
        self.assertFalse(reply["ok"])
        self.assertEqual("no_commercial_auth_fields", reply["error"])


if __name__ == "__main__":
    unittest.main()
