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
        self.lab = Laboratory(
            root / "state", key.public_key(), "song-plus-dmi-2021", "host-lab"
        )
        self.server = Server(0, self.lab)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
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
        self.assertEqual("waiting_video", started["state"])
        beat = _exchange(
            self.port,
            {
                "op": "cast.heartbeat",
                "epoch": started["epoch"],
                "revision": started["revision"],
            },
        )
        self.assertTrue(beat["ok"])
        music = _exchange(self.port, {"op": "music.show", "music": {"title": "x"}})
        self.assertFalse(music["ok"])
        self.assertEqual("native_overlay_unavailable", music["error"])
        stopped = _exchange(
            self.port,
            {
                "op": "cast.stop",
                "request_id": "t2",
                "epoch": started["epoch"],
                "expected_revision": started["revision"],
            },
        )
        self.assertEqual("idle", stopped["state"])
        self.assertFalse(stopped["target_casting"])

    def test_old_session_cannot_stop_or_keep_alive_new_revision(self):
        initial = self.lab.cast_status()
        request = {
            "op": "cast.start",
            "request_id": "same",
            "epoch": initial["epoch"],
            "expected_revision": 0,
            "profile_id": 1,
        }
        first = self.lab.cast_start(request)
        self.assertTrue(self.lab.cast_start(request)["duplicate"])
        self.assertEqual(1, self.lab.cast_revision)
        with self.assertRaisesRegex(ValueError, "request_id_conflict"):
            self.lab.cast_start(dict(request, profile_id=2))
        second = self.lab.cast_switch(
            {
                "op": "cast.switch",
                "request_id": "second",
                "epoch": initial["epoch"],
                "expected_revision": first["revision"],
                "profile_id": 2,
            }
        )
        with self.assertRaisesRegex(ValueError, "revision_conflict"):
            self.lab.cast_heartbeat(
                {"epoch": initial["epoch"], "revision": first["revision"]}
            )
        with self.assertRaisesRegex(ValueError, "epoch_mismatch"):
            self.lab.cast_stop(
                {"request_id": "old", "epoch": "wrong", "expected_revision": 2}
            )
        with self.assertRaisesRegex(ValueError, "revision_conflict"):
            self.lab.cast_stop(
                {"request_id": "old", "epoch": initial["epoch"], "expected_revision": 1}
            )
        self.assertEqual(second["revision"], self.lab.cast_revision)
        self.assertEqual("waiting_video", self.lab.cast_state)
        self.assertFalse(second["visual_verified"])

    def test_cast_and_updates_are_exclusive(self):
        status = self.lab.cast_status()
        self.lab.cast_start(
            {"request_id": "one", "epoch": status["epoch"], "expected_revision": 0}
        )
        with self.assertRaisesRegex(ValueError, "cast_active"):
            self.lab.reserve(
                {"update_id": "0" * 32, "kind": "components", "bytes": 100}
            )
        with self.assertRaisesRegex(ValueError, "cast_active"):
            self.lab.apply("0" * 32)

    def test_rejects_commercial_auth_fields(self) -> None:
        reply = _exchange(self.port, {"op": "cast.status", "token": "abc"})
        self.assertFalse(reply["ok"])
        self.assertEqual("no_commercial_auth_fields", reply["error"])


if __name__ == "__main__":
    unittest.main()
