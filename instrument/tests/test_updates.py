from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from unittest import mock
import uuid

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from openbyddisplay.packages import DOMAIN, HEADER, build, verify
from openbyddisplay.service import Laboratory, Server, atomic_json


class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "version.txt").write_text("independent development service")
        self.key = Ed25519PrivateKey.generate()
        self.target = "song-plus-dmi-2021"
        self.lab = Laboratory(self.root / "state", self.key.public_key(), self.target)

    def package(self, version="0.1.0", target=None):
        return build(self.source, self.key, target or self.target, "host-lab", version)

    def stage(self, raw):
        job_id = uuid.uuid4().hex
        replies = []
        self.lab.receive(
            {"update_id": job_id, "kind": "components", "bytes": len(raw)},
            io.BytesIO(raw),
            replies.append,
        )
        self.assertTrue(replies[0]["ready"])
        self.assertEqual("ready", replies[-1]["stage"])
        return job_id

    def test_both_target_packages_are_distinct_and_bound(self):
        for target in ("song-plus-dmi-2021", "tang-plus-dmi-2021"):
            manifest, files = verify(
                self.package(target=target), self.key.public_key(), target, "host-lab"
            )
            self.assertEqual(2, manifest["format"])
            self.assertEqual(target, json.loads(files["xhh-target.json"])["target"])
        with self.assertRaises(ValueError):
            verify(
                self.package(target="tang-plus-dmi-2021"),
                self.key.public_key(),
                self.target,
                "host-lab",
            )

    def test_wrong_hardware_and_key_are_rejected(self):
        with self.assertRaises(ValueError):
            verify(
                self.package(), self.key.public_key(), self.target, "another-instrument"
            )
        with self.assertRaises(InvalidSignature):
            verify(
                self.package(),
                Ed25519PrivateKey.generate().public_key(),
                self.target,
                "host-lab",
            )

    def test_modified_payload_and_manifest_are_rejected(self):
        for location in (-1, HEADER.size + 2):
            raw = bytearray(self.package())
            raw[location] ^= 1
            with self.assertRaises((ValueError, InvalidSignature)):
                verify(bytes(raw), self.key.public_key(), self.target, "host-lab")

    def test_traversal_and_ancestor_paths_fail_even_with_valid_signature(self):
        for name in ("../outside", "/absolute", "services/version.txt/child"):
            raw = self.package()
            magic, size, reserved, payload_size, _ = HEADER.unpack_from(raw)
            manifest = json.loads(raw[HEADER.size : HEADER.size + size])
            manifest["files"].append(
                {"path": name, "bytes": 0, "sha256": hashlib.sha256(b"").hexdigest()}
            )
            data = json.dumps(manifest).encode()
            edited = (
                HEADER.pack(
                    magic,
                    len(data),
                    reserved,
                    payload_size,
                    self.key.sign(DOMAIN + data),
                )
                + data
                + raw[HEADER.size + size :]
            )
            with self.assertRaises(ValueError):
                verify(edited, self.key.public_key(), self.target, "host-lab")

    def test_install_only_publishes_sandbox_data_never_runs_script(self):
        job_id = self.stage(self.package())
        reply = self.lab.apply(job_id)
        self.assertTrue(reply["applied"])
        current = self.lab.current()
        directory = self.lab.root / "generations" / current["package_id"]
        self.assertFalse((directory / "install.sh").exists())
        self.assertEqual(
            (self.source / "version.txt").read_bytes(),
            (directory / "services/version.txt").read_bytes(),
        )
        self.assertTrue(self.lab.apply(job_id)["duplicate"])

    def test_rollback_is_not_silently_allowed(self):
        self.lab.apply(self.stage(self.package("0.2.0")))
        with self.assertRaisesRegex(ValueError, "version_not_newer"):
            self.lab.apply(self.stage(self.package("0.1.0")))
        self.assertEqual("0.2.0", self.lab.current()["version"])

    def test_publish_failure_preserves_previous_generation(self):
        self.lab.apply(self.stage(self.package()))
        previous = self.lab.current()
        raw = self.package("0.2.0")
        job_id = self.stage(raw)
        replace = os.replace

        def fail_pointer(source, destination):
            if Path(destination) == self.lab.root / "current.json":
                raise OSError("storage_failure")
            return replace(source, destination)

        with mock.patch("openbyddisplay.service.os.replace", side_effect=fail_pointer):
            with self.assertRaises(OSError):
                self.lab.apply(job_id)
        self.assertEqual(previous, self.lab.current())
        self.assertEqual("failed", self.lab.load(job_id)["stage"])
        self.lab.cancel(job_id)
        self.assertTrue(self.lab.apply(self.stage(raw))["applied"])

    def test_lost_final_receipt_does_not_turn_ready_job_into_failure(self):
        raw, job_id = self.package(), uuid.uuid4().hex

        def send(reply):
            if reply.get("stage") == "ready":
                raise BrokenPipeError("client_disconnected")

        with self.assertRaises(BrokenPipeError):
            self.lab.receive(
                {"update_id": job_id, "kind": "components", "bytes": len(raw)},
                io.BytesIO(raw),
                send,
            )
        self.assertEqual("ready", self.lab.load(job_id)["stage"])
        self.assertTrue(self.lab.apply(job_id)["applied"])

    def test_restart_does_not_resume_unconfirmed_install(self):
        job_id = self.stage(self.package())
        state = self.lab.load(job_id)
        state.update(stage="installing", worker_active=True)
        self.lab.save(state)
        restarted = Laboratory(self.lab.root, self.key.public_key(), self.target)
        self.assertEqual("recovery_required", restarted.load(job_id)["stage"])
        self.assertEqual({}, restarted.current())

    def test_restart_confirms_already_published_generation_without_reinstalling(self):
        job_id = self.stage(self.package())
        self.lab.apply(job_id)
        state = self.lab.load(job_id)
        atomic_json(
            self.lab.root / "current.json",
            {"package_id": state["package_id"], "version": "0.1.0"},
        )
        state.update(stage="installing", worker_active=True)
        self.lab.save(state)
        restarted = Laboratory(self.lab.root, self.key.public_key(), self.target)
        self.assertTrue(restarted.load(job_id)["applied"])

    def test_restart_does_not_confirm_a_missing_generation(self):
        job_id = self.stage(self.package())
        state = self.lab.load(job_id)
        atomic_json(
            self.lab.root / "current.json",
            {"package_id": state["package_id"], "version": "0.1.0"},
        )
        state.update(stage="installing", worker_active=True)
        self.lab.save(state)
        restarted = Laboratory(self.lab.root, self.key.public_key(), self.target)
        self.assertEqual("recovery_required", restarted.load(job_id)["stage"])

    def test_interrupted_upload_fails_and_can_be_cleared(self):
        raw = self.package()
        job_id = uuid.uuid4().hex
        with self.assertRaises(ValueError):
            self.lab.receive(
                {"update_id": job_id, "kind": "components", "bytes": len(raw)},
                io.BytesIO(raw[:12]),
                lambda _: None,
            )
        self.assertEqual("failed", self.lab.load(job_id)["stage"])
        self.assertEqual("cancelled", self.lab.cancel(job_id)["stage"])

    def test_socket_flow_has_no_account_or_token(self):
        server = Server(0, self.lab)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        port = server.server_address[1]

        def command(body):
            with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
                sock.sendall(json.dumps(body).encode() + b"\n")
                with sock.makefile("rb") as reader:
                    return json.loads(reader.readline())

        self.assertEqual("none", command({"op": "update.info"})["authentication"])
        self.assertEqual("idle", command({"op": "cast.status"})["state"])
        self.assertFalse(command({"op": "update.info", "token": "unwanted"})["ok"])
        raw, job_id = self.package(), uuid.uuid4().hex
        with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
            sock.sendall(
                json.dumps(
                    {
                        "op": "update.upload",
                        "update_id": job_id,
                        "kind": "components",
                        "bytes": len(raw),
                    }
                ).encode()
                + b"\n"
            )
            with sock.makefile("rb") as reader:
                self.assertTrue(json.loads(reader.readline())["ready"])
                sock.sendall(raw)
                self.assertEqual("ready", json.loads(reader.readline())["stage"])
        self.assertFalse(command({"op": "update.apply", "update_id": job_id})["ok"])
        self.assertTrue(
            command(
                {
                    "op": "update.apply",
                    "update_id": job_id,
                    "parked": True,
                    "confirm": "INSTALL_COMPONENTS",
                }
            )["applied"]
        )


if __name__ == "__main__":
    unittest.main()
