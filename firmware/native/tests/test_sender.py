"""Check diagnostic sender receipts and fail-before-connect input validation."""

import io
import json
from pathlib import Path
import struct
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from send_video import send_video  # noqa: E402

FRAME = b"\0\0\0\1\x09\xf0\0\0\0\1\x67\x01\0\0\0\1\x68\x01\0\0\0\1\x65\x01"
READY = {"ok": True, "ready": True, "protocol": "LHF2", "service": "openbyddisplay.video", "wire_version": 1}


class Connection:
    def __init__(self, replies):
        self.incoming = io.BytesIO(replies)
        self.outgoing = bytearray()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def settimeout(self, value):
        if not 0 < value <= 5:
            raise AssertionError("Unbounded I/O")

    def sendall(self, data):
        self.outgoing.extend(data)

    def recv(self, count):
        return self.incoming.read(count)


class SenderTests(unittest.TestCase):
    def send(self, replies):
        connection = Connection(replies)
        with patch("send_video.socket.create_connection", return_value=connection):
            result = send_video("127.0.0.1", 47782, FRAME, 64, 32, 5)
        return result, connection

    def test_strict_receipts_and_eos(self):
        replies = b"".join(
            json.dumps(value).encode() + b"\n"
            for value in (
                READY,
                {"ok": True, "accepted_sequence": 0, "ack": 0},
                {"ok": True, "done": True, "cleanup": True},
            )
        )
        result, connection = self.send(replies)
        self.assertEqual(result["frames_sent"], 1)
        self.assertFalse(result["vehicle_visual_verified"])
        self.assertTrue(connection.outgoing.startswith(b"BVL1"))
        self.assertTrue(
            connection.outgoing.endswith(
                struct.pack(">4s5I", b"LHF2", 0, 0xFFFFFFFF, 0, 0, 0)
            )
        )

    def test_wrong_or_missing_receipts_are_failures(self):
        ready = json.dumps(READY).encode() + b"\n"
        ack = b'{"ok":true,"accepted_sequence":0}\n'
        invalid = (
            b'{"ok":true,"ready":false}\n',
            b'{"ok":true,"ready":true,"protocol":"other"}\n',
            ready + b'{"ok":true,"accepted_sequence":1}\n',
            ready + b'{"ok":true,"accepted_sequence":false}\n',
            ready + b'{"ok":true,"accepted_sequence":0.0}\n',
            ready + b'{"ok":true,"accepted_sequence":0,"ack":1}\n',
            ready + b'{"ok":true,"accepted_sequence":0,"ack":false}\n',
            ready + b'{"ok":true,"accepted_sequence":0,"ack":0.0}\n',
            ready + ack + b'{"ok":true,"done":true,"cleanup":false}\n',
            ready + ack + b'{"ok":true,"done":true,"cleanup":true,"frames":false}\n',
            ready + ack + b'{"ok":true,"done":true,"cleanup":true,"submitted":2}\n',
            ready + ack,
            b'{"ok":false}\n',
            b"[]\n",
            b" " * 4097,
        )
        for response in invalid:
            with self.subTest(response=response[:80]):
                with self.assertRaises((ValueError, ConnectionError)):
                    self.send(response)

    def test_invalid_input_never_opens_a_connection(self):
        invalid = (
            ("0.0.0.0", 47782, FRAME, 64, 32, 5),
            ("8.8.8.8", 47782, FRAME, 64, 32, 5),
            ("240.1.1.1", 47782, FRAME, 64, 32, 5),
            ("127.0.0.1", 80, FRAME, 64, 32, 5),
            ("127.0.0.1", 47782, FRAME, 63, 32, 5),
            ("127.0.0.1", 47782, FRAME, 64, 32, 0),
            ("127.0.0.1", 47782, b"", 64, 32, 5),
            ("127.0.0.1", 47782, FRAME[6:], 64, 32, 5),
            ("127.0.0.1", 47782, FRAME * 61, 64, 32, 5),
        )
        with patch("send_video.socket.create_connection") as connect:
            for args in invalid:
                with self.subTest(args=args[:2]):
                    with self.assertRaises(ValueError):
                        send_video(*args)
            connect.assert_not_called()

    def test_foreign_identity_never_receives_video(self):
        for field, value in (("service", "other"), ("service", None), ("wire_version", True), ("wire_version", 2)):
            connection = Connection(json.dumps(dict(READY, **{field: value})).encode() + b"\n")
            with patch("send_video.socket.create_connection", return_value=connection):
                with self.assertRaises(ValueError):
                    send_video("127.0.0.1", 47782, FRAME, 64, 32, 5)
            self.assertEqual(39, len(connection.outgoing))  # Greeting only, no video payload.


if __name__ == "__main__":
    unittest.main()
