"""Real sockets and C framing/lifetime, with explicit QNX service doubles."""

import ctypes as C
import json
import socket
import struct
import sys
import threading
import unittest
import zlib

from check_media import Api, Config, Media, Model, load

KEY = b"\0\0\0\1\x67\x01\0\0\0\1\x68\x01\0\0\0\1\x65\x01"
DELTA = b"\0\0\0\1\x41\x01"
HELLO = b'BVL1\0\0\0\x0f{"op":"stream"}' + struct.pack(">4I", 64, 48, 5, 0)
EOS = b"LHF2" + struct.pack(">5I", 0, 0xFFFFFFFF, 0, 0, 0)


class RenderModel(Model):
    def invoke(self, name, args):
        result = super().invoke(name, args)
        if name == "MsgSendvs_r" and self.messages[-1][0] == 1:
            if not self.media.child:
                child = self.child()
            else:
                child = self.media.child
            self.events.append((9, child))
        return result


class Peer:
    def __init__(self, library, continuous=1, model_class=RenderModel):
        self.model = model_class(library)
        self.client, self.server = socket.socketpair()
        self.server.setblocking(False)
        self.client.settimeout(8)
        self.input = self.client.makefile("rb", buffering=0)
        self.cancel = C.c_int(0)
        self.result = None

        def serve():
            self.result = library.obd_stream_run(
                C.byref(self.model.media),
                C.byref(self.model.api),
                C.byref(self.model.config),
                self.server.fileno(),
                self.server.fileno(),
                continuous,
                C.byref(self.cancel),
            )
            self.server.close()

        self.thread = threading.Thread(target=serve, daemon=True)
        self.thread.start()

    def reply(self):
        return json.loads(self.input.readline(4097))

    def send(self, sequence, payload=KEY, corrupt=False):
        self.client.sendall(
            b"LHF2"
            + struct.pack(
                ">5I",
                len(payload),
                sequence,
                sequence * 200,
                int(payload == KEY),
                zlib.crc32(payload) ^ int(corrupt),
            )
            + payload
        )
        return self.reply()

    def close(self):
        self.input.close()
        self.client.close()
        self.thread.join(8)
        if self.thread.is_alive():
            raise RuntimeError("Native stream did not terminate")


def stream_library(path):
    lib = load(path)
    lib.obd_stream_run.argtypes = [
        C.POINTER(Media),
        C.POINTER(Api),
        C.POINTER(Config),
        C.c_int,
        C.c_int,
        C.c_int,
        C.POINTER(C.c_int),
    ]
    return lib


class StreamTests(unittest.TestCase):
    def test_capabilities_does_not_allocate_media_resources(self):
        peer = Peer(LIB)
        try:
            peer.client.sendall(b"INFO")
            info = peer.reply()
            self.assertEqual(info["service"], "openbyddisplay.video")
            self.assertFalse(info["production_flashable"])
            self.assertEqual(peer.model.calls, 0)
        finally:
            peer.close()
        self.assertEqual(peer.result, 0)

    def test_open_failure_with_failed_cleanup_blocks_readmission(self):
        class FailedCleanup(RenderModel):
            def invoke(self, name, args):
                if name in ("screen_create_event", "screen_destroy_window"):
                    return -1
                return super().invoke(name, args)

        peer = Peer(LIB, model_class=FailedCleanup)
        try:
            peer.client.sendall(HELLO)
            self.assertEqual(peer.input.read(), b"")
        finally:
            peer.close()
        self.assertEqual(peer.result, 75)
        self.assertEqual(peer.model.media.failed, 2)

    def test_continuous_heartbeat_window_ack_and_clean_eos(self):
        peer = Peer(LIB)
        try:
            # Fragmentation must not change the handshake interpretation.
            for byte in HELLO:
                peer.client.sendall(bytes([byte]))
            ready = peer.reply()
            self.assertTrue(ready["continuous"])
            self.assertEqual(ready["bounds"], [1, 2, 60, 44])
            peer.client.sendall(b"CONF" + struct.pack(">6I", 7, 2, 3, 40, 30, 220))
            configured = peer.reply()
            self.assertEqual(configured["configured"], 7)
            self.assertFalse(configured["property_readback"])
            for sequence in range(70):
                ack = peer.send(sequence, KEY if sequence % 10 == 0 else DELTA)
                self.assertEqual(ack["accepted_sequence"], sequence)
                self.assertEqual(ack["submitted"], sequence + 1)
                if sequence == 35:
                    peer.client.sendall(b"PING" + struct.pack(">I", 0xF1234567))
                    self.assertEqual(peer.reply()["heartbeat"], 0xF1234567)
            peer.client.sendall(EOS)
            done = peer.reply()
            self.assertTrue(done["done"] and done["cleanup"])
            self.assertTrue(done["property_readback"])
            self.assertEqual(done["submitted"], 70)
        finally:
            peer.close()
        self.assertEqual(peer.result, 0)
        peer.model.check_closed(self)

    def test_bad_crc_and_out_of_bounds_configuration_close_owned_resources(self):
        for bad_config in (False, True):
            peer = Peer(LIB)
            try:
                peer.client.sendall(HELLO)
                peer.reply()
                if bad_config:
                    peer.client.sendall(
                        b"CONF" + struct.pack(">6I", 1, 0, 0, 64, 48, 255)
                    )
                    reply = peer.reply()
                else:
                    reply = peer.send(0, corrupt=True)
                self.assertFalse(reply["ok"])
                self.assertEqual(reply["submitted"], 0)
            finally:
                peer.close()
            self.assertNotEqual(peer.result, 0)
            peer.model.check_closed(self)

    def test_disconnect_then_new_session_requires_new_keyframe(self):
        first = Peer(LIB)
        first.client.sendall(HELLO)
        first.reply()
        self.assertTrue(first.send(0)["ok"])
        first.close()
        first.model.check_closed(self)
        second = Peer(LIB)
        try:
            second.client.sendall(HELLO)
            second.reply()
            self.assertFalse(second.send(1, DELTA)["ok"])
        finally:
            second.close()
        second.model.check_closed(self)


if __name__ == "__main__":
    LIB = stream_library(sys.argv.pop(1))
    unittest.main()
