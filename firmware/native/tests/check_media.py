"""Execute the C media engine with explicit host ABI doubles, never QNX services."""

import ctypes as C
from pathlib import Path
import re
import sys
import unittest
import zlib


class Iov(C.Structure):
    _fields_ = [("base", C.c_void_p), ("bytes", C.c_uint)]


TYPES = {
    "void **": C.POINTER(C.c_void_p),
    "void *": C.c_void_p,
    "const void *": C.c_void_p,
    "int": C.c_int,
    "unsigned": C.c_uint,
    "int *": C.POINTER(C.c_int),
    "const int *": C.POINTER(C.c_int),
    "char *": C.c_void_p,
    "const char *": C.c_char_p,
    "uint64_t": C.c_uint64,
    "uint64_t *": C.POINTER(C.c_uint64),
    "const uint64_t *": C.POINTER(C.c_uint64),
    "const obd_iov *": C.POINTER(Iov),
}
header = (Path(__file__).resolve().parents[1] / "qnx66/media.h").read_text()
declarations = re.findall(r"(int|void \*|void)\s*\(\*(\w+)\)\(([^;]+)\);", header)
callbacks = {}
for returns, name, args in declarations:
    parameters = [] if args == "void" else [TYPES[a.strip()] for a in args.split(",")]
    callbacks[name] = C.CFUNCTYPE(
        C.c_void_p if returns == "void *" else None if returns == "void" else C.c_int,
        *parameters,
    )


class Api(C.Structure):
    _fields_ = list(callbacks.items())


class Config(C.Structure):
    _fields_ = (
        [("session_id", C.c_char_p)]
        + [
            (n, C.c_int)
            for n in (
                "display_id",
                "physical_width",
                "physical_height",
                "x",
                "y",
                "width",
                "height",
                "opacity",
                "zorder",
            )
        ]
        + [(n, C.c_uint) for n in ("encoded_width", "encoded_height", "fps")]
    )


class Sequence(C.Structure):
    _fields_ = [
        ("has_previous", C.c_int),
        ("sequence", C.c_uint32),
        ("pts_ms", C.c_uint32),
    ]


class Frame(C.Structure):
    _fields_ = [
        (n, C.c_uint32) for n in ("bytes", "sequence", "pts_ms", "key_frame", "crc")
    ]


class Media(C.Structure):
    _fields_ = (
        [("api", Api), ("config", Config)]
        + [
            (n, C.c_void_p)
            for n in ("screen", "parent", "child", "event", "connection", "renderer")
        ]
        + [
            (n, C.c_int)
            for n in (
                "output",
                "channel",
                "attached",
                "playing",
                "ready",
                "failed",
                "readback",
                "actual_visible",
            )
        ]
        + [("submitted", C.c_uint), ("posts", C.c_uint), ("sequence", Sequence)]
        + [(n, C.c_char * 64) for n in ("group", "video", "channel_name")]
    )


class Model:
    def __init__(self, library, fail_at=0):
        self.library, self.fail_at = library, fail_at
        self.calls = self.next = 0
        self.objects, self.errors, self.trace, self.events, self.messages = (
            {},
            [],
            [],
            [],
            [],
        )
        self.remote_windows = set()
        self.output = self.attached = self.playing = self.channel = False
        self.pixels = C.create_string_buffer(64 * 48 * 4)
        self.config = Config(b"HostTest1", 7, 64, 48, 1, 2, 60, 44, 255, 3, 64, 48, 5)
        self.media = Media()
        self.current_event = (0, 0)
        self.api = Api()
        self.refs = []
        for name, prototype in callbacks.items():

            def call(*args, _name=name):
                try:
                    return self.invoke(_name, args)
                except Exception as error:
                    self.errors.append(f"{_name}: {error}")
                    return (
                        None
                        if _name in ("mmr_disconnect", "strm_dict_destroy")
                        else 0
                        if _name
                        in (
                            "mmr_connect",
                            "mmr_context_create",
                            "strm_dict_new",
                            "strm_dict_set",
                        )
                        else -1
                    )

            callback = prototype(call)
            self.refs.append(callback)
            setattr(self.api, name, callback)

    def allocate(self, kind):
        self.next += 1
        handle = 4096 + self.next
        self.objects[handle] = {"kind": kind, "properties": {}}
        return handle

    def release(self, handle):
        if handle not in self.objects:
            raise RuntimeError("double release or foreign handle")
        del self.objects[handle]

    def invoke(self, name, a):
        self.trace.append(name)
        cleanup = name.startswith("screen_destroy") or name in (
            "mmr_stop",
            "mmr_input_detach",
            "mmr_output_detach",
            "mmr_context_destroy",
            "mmr_disconnect",
            "strm_dict_destroy",
            "name_close",
        )
        if not cleanup:
            self.calls += 1
        if not cleanup and self.calls == self.fail_at:
            self.fail_at = 0
            if name.startswith("mmr_") and name.endswith("parameters"):
                self.release(a[-1])  # API owns parameters even on failure.
            return (
                0
                if name
                in (
                    "mmr_connect",
                    "mmr_context_create",
                    "strm_dict_new",
                    "strm_dict_set",
                )
                else -1
            )
        if name in (
            "screen_create_context",
            "screen_create_event",
            "screen_create_window_type",
        ):
            a[0][0] = self.allocate(name)
        elif name.startswith("screen_destroy") or name in (
            "mmr_context_destroy",
            "mmr_disconnect",
            "strm_dict_destroy",
        ):
            self.release(a[0])
        elif name == "screen_get_context_property_iv":
            a[2][0] = 1
        elif name == "screen_get_context_property_pv":
            a[2][0] = 123
        elif name == "screen_get_display_property_iv":
            if a[1] == 87:
                a[2][0] = 7
            else:
                a[2][0], a[2][1] = 64, 48
        elif name == "screen_set_window_property_iv":
            self.objects[a[0]]["properties"][a[1]] = [
                a[2][i] for i in range(2 if a[1] in (5, 35, 40) else 1)
            ]
        elif name == "screen_get_window_property_iv":
            values = [2] if a[1] == 47 else self.objects[a[0]]["properties"][a[1]]
            for i, value in enumerate(values):
                a[2][i] = value
        elif name == "screen_get_window_property_pv":
            a[2][0] = 456
        elif name == "screen_get_buffer_property_iv":
            a[2][0] = 256 if a[1] == 44 else 64
            if a[1] == 5:
                a[2][1] = 48
        elif name == "screen_get_buffer_property_pv":
            a[2][0] = C.addressof(self.pixels)
        elif name == "screen_get_window_property_cv":
            record = self.objects[a[0]]
            value = record["id"] if a[1] == 20 else record["group"]
            C.memmove(a[3], value + b"\0", min(a[2], len(value) + 1))
        elif name == "screen_get_event":
            self.current_event = self.events.pop(0) if self.events else (0, 0)
        elif name == "screen_get_event_property_iv":
            a[2][0] = self.current_event[0]
        elif name == "screen_get_event_property_pv":
            a[2][0] = self.current_event[1]
        elif name in ("mmr_connect", "mmr_context_create", "strm_dict_new"):
            return self.allocate(name)
        elif name == "strm_dict_set":
            self.release(a[0])
            return self.allocate(name)
        elif name.startswith("mmr_") and name.endswith("parameters"):
            self.release(a[-1])
        elif name == "mmr_output_attach":
            if b"initflags=invisible" not in a[1] or self.attached:
                raise RuntimeError("output attachment order/visibility")
            self.output = True
            return 0  # Zero is a valid output ID.
        elif name == "mmr_input_attach":
            if not self.output or b"mdevpush:/dev/name/local/obd_stream_" not in a[1]:
                raise RuntimeError("input contract")
            self.attached = True
        elif name == "mmr_play":
            self.playing = True
        elif name == "name_open":
            self.channel = True
            return 0  # Zero is also a valid coid.
        elif name == "name_close":
            self.channel = False
        elif name == "mmr_stop":
            self.playing = False
        elif name == "mmr_input_detach":
            self.attached = False
        elif name == "mmr_output_detach":
            self.output = False
        elif name == "TimerTimeout":
            if a[0] != 2 or a[1] != 0x50:
                raise RuntimeError("timeout ABI")
        elif name == "MsgSendvs_r":
            vectors = a[1]
            header = bytes(C.string_at(vectors[0].base, vectors[0].bytes))
            if len(header) != 24 or a[2] != 2:
                raise RuntimeError("IPC envelope")
            import struct

            fields = struct.unpack("<6I", header)
            payload = C.string_at(vectors[1].base, vectors[1].bytes)
            if fields[:4] != (0, 0, 0, 0) or fields[5] != len(payload):
                raise RuntimeError("IPC header")
            if fields[4] == 2 and struct.unpack("<8I", payload) != (
                0,
                0x48323634,
                64,
                48,
                24,
                5,
                0,
                0,
            ):
                raise RuntimeError("format descriptor")
            self.messages.append((fields[4], payload))
        return 0

    def open(self):
        return self.library.obd_media_open(
            C.byref(self.media), C.byref(self.api), C.byref(self.config)
        )

    def close(self):
        return self.library.obd_media_close(C.byref(self.media))

    def drain(self):
        return self.library.obd_media_events(C.byref(self.media))

    def child(self, owned=True):
        handle = self.allocate("child")
        self.remote_windows.add(handle)
        self.objects[handle].update(
            id=bytes(self.media.video) if owned else b"OEM_window",
            group=bytes(self.media.group),
        )
        self.events.append((1, handle))
        return handle

    def submit(self, data, sequence=0, pts=0, key=1):
        frame = Frame(len(data), sequence, pts, key, zlib.crc32(data))
        buffer = C.create_string_buffer(data)
        return self.library.obd_media_submit(
            C.byref(self.media), C.byref(frame), buffer, len(data)
        )

    def check_closed(self, test):
        test.assertFalse(self.errors, self.errors)
        test.assertFalse(self.objects, self.objects)
        test.assertFalse(self.output or self.attached or self.playing or self.channel)


class MediaTests(unittest.TestCase):
    def test_each_initialization_failure_releases_every_resource(self):
        baseline = Model(LIB)
        self.assertEqual(baseline.open(), 0)
        calls = baseline.calls
        self.assertEqual(baseline.media.ready, 1)
        self.assertEqual(baseline.media.posts, 0)
        baseline.close()
        baseline.check_closed(self)
        for point in range(1, calls + 1):
            with self.subTest(point=point):
                model = Model(LIB, point)
                self.assertNotEqual(model.open(), 0)
                model.close()
                model.check_closed(self)

    def test_owned_window_readback_and_posts_are_separate(self):
        model = Model(LIB)
        self.assertEqual(model.open(), 0)
        child = model.child()
        self.assertEqual(model.drain(), 0)
        self.assertEqual(model.media.readback, 1)
        self.assertEqual(model.media.posts, 0)
        model.events.append((9, child))
        self.assertEqual(model.drain(), 0)
        self.assertEqual(model.media.posts, 1)
        model.close()
        model.close()
        model.check_closed(self)

    def test_foreign_window_local_reference_is_released_without_changing_remote(self):
        model = Model(LIB)
        self.assertEqual(model.open(), 0)
        foreign = model.child(False)
        self.assertEqual(model.drain(), 0)
        self.assertFalse(model.media.child)
        self.assertNotIn(foreign, model.objects)
        self.assertIn(foreign, model.remote_windows)
        model.close()
        model.check_closed(self)

    def test_crc_and_submit_failure_do_not_commit_sequence(self):
        model = Model(LIB)
        self.assertEqual(model.open(), 0)
        data = b"\0\0\0\1\x67\x01\0\0\0\1\x68\x01\0\0\0\1\x65\x01"
        self.assertEqual(model.submit(data), 0)
        model.fail_at = model.calls + 2  # arm timer succeeds, MsgSend fails
        self.assertNotEqual(model.submit(data, 1, 1), 0)
        self.assertEqual(model.media.sequence.sequence, 0)
        self.assertEqual(model.media.submitted, 1)
        model.close()
        model.check_closed(self)

    def test_bad_crc_is_rejected_before_ipc_and_sequence_commit(self):
        model = Model(LIB)
        self.assertEqual(model.open(), 0)
        data = C.create_string_buffer(b"\0\0\0\1\x65\x01")
        frame = Frame(6, 0, 0, 1, 0)
        self.assertNotEqual(
            LIB.obd_media_submit(C.byref(model.media), C.byref(frame), data, 6), 0
        )
        self.assertEqual(model.media.submitted, 0)
        self.assertEqual(model.media.sequence.has_previous, 0)
        self.assertEqual(len(model.messages), 1)  # Format only, no video payload.
        model.close()
        model.check_closed(self)

    def test_owned_child_close_fails_and_releases_the_session(self):
        model = Model(LIB)
        self.assertEqual(model.open(), 0)
        child = model.child()
        model.drain()
        model.events.append((3, child))
        self.assertNotEqual(model.drain(), 0)
        model.close()
        model.check_closed(self)

    def test_owned_child_z_order_drift_fails_readback_without_an_event(self):
        model = Model(LIB)
        self.assertEqual(model.open(), 0)
        child = model.child()
        self.assertEqual(model.drain(), 0)
        model.objects[child]["properties"][54] = [-10]
        self.assertNotEqual(model.drain(), 0)
        self.assertEqual(model.media.ready, 0)
        model.close()
        model.check_closed(self)


def load(path):
    library = C.CDLL(str(Path(path).resolve()))
    library.obd_media_open.argtypes = [
        C.POINTER(Media),
        C.POINTER(Api),
        C.POINTER(Config),
    ]
    library.obd_media_close.argtypes = library.obd_media_events.argtypes = [
        C.POINTER(Media)
    ]
    library.obd_media_submit.argtypes = [
        C.POINTER(Media),
        C.POINTER(Frame),
        C.c_void_p,
        C.c_size_t,
    ]
    return library


if __name__ == "__main__":
    LIB = load(sys.argv.pop(1))
    unittest.main()
