#!/usr/bin/env python3
import argparse
import socket
import struct


CORE_KEYBOARD = 0x0100
CORE_POINTER = 0x0200
KBD_FEEDBACK = 0
LED_FEEDBACK = 4
DEFAULT_CLASS = 0x0300
DEFAULT_ID = 0x0400
ALL_CLASSES = 0x0500
ALL_IDS = 0x0600
BUTTON_ACTIONS = 0x0002
INDICATOR_NAMES = 0x0004
INDICATOR_MAPS = 0x0008
INDICATOR_STATE = 0x0010
INDICATORS = INDICATOR_NAMES | INDICATOR_MAPS | INDICATOR_STATE
BAD_VALUE = 2
BAD_MATCH = 8


def bit_count_32(value):
    return bin(value & 0xFFFFFFFF).count("1")


def request(opcode, minor, body=b""):
    size = 4 + len(body)
    if size % 4:
        raise AssertionError(f"unaligned request size: {size}")
    return bytes((opcode, minor)) + struct.pack("<H", size // 4) + body


def read_exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError(f"needed {size} bytes, got {len(data)}")
        data.extend(chunk)
    return bytes(data)


def read_packet(sock):
    head = read_exact(sock, 32)
    if head[0] == 1:
        extra = struct.unpack_from("<I", head, 4)[0] * 4
        return head + read_exact(sock, extra)
    return head


def setup(sock):
    sock.sendall(bytes((0x6C, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    prefix = read_exact(sock, 8)
    if prefix[0] != 1:
        raise AssertionError(f"X11 setup failed: {prefix.hex()}")
    read_exact(sock, struct.unpack_from("<H", prefix, 6)[0] * 4)


def query_extension(sock, name):
    encoded = name.encode("ascii")
    body = struct.pack("<H2x", len(encoded)) + encoded
    body += b"\0" * ((-len(body)) % 4)
    sock.sendall(request(98, 0, body))
    reply = read_packet(sock)
    if reply[0] != 1 or reply[8] == 0:
        raise AssertionError(f"{name} is unavailable: {reply.hex()}")
    return reply[9]


def set_device_info(
    major,
    device,
    led_class,
    led_id,
    *,
    change=INDICATOR_STATE,
    state=1,
    first_button=0,
    button_actions=(),
    names_present=0,
    maps_present=0,
    phys_indicators=0,
    names=(),
    maps=(),
):
    body = struct.pack(
        "<HBBHH",
        device,
        first_button,
        len(button_actions),
        change,
        1 if change & INDICATORS else 0,
    )
    for action in button_actions:
        if len(action) != 8:
            raise AssertionError("button actions must contain exactly 8 bytes")
        body += action
    if change & INDICATORS:
        if len(names) != bit_count_32(names_present):
            raise AssertionError("name count does not match names_present")
        if len(maps) != bit_count_32(maps_present) or any(len(item) != 12 for item in maps):
            raise AssertionError("map records do not match maps_present")
        body += struct.pack(
            "<HHIIII", led_class, led_id, names_present, maps_present, phys_indicators, state
        )
        body += b"".join(struct.pack("<I", name) for name in names)
        body += b"".join(maps)
    return request(major, 25, body)


def get_device_info(major, device, wanted, led_class=0, led_id=0, first_button=0, button_count=0):
    body = struct.pack(
        "<HHBBBBHH",
        device,
        wanted,
        0,
        first_button,
        button_count,
        0,
        led_class,
        led_id,
    )
    return request(major, 24, body)


def get_indicator_state(major):
    return request(major, 12, struct.pack("<H2x", CORE_KEYBOARD))


def get_indicator_map(major, which):
    return request(major, 13, struct.pack("<H2xI", CORE_KEYBOARD, which))


def expect_void_result(sock, encoded_request, error=None, bad_value=0):
    sock.sendall(encoded_request)
    sock.sendall(request(43, 0))
    packet = read_packet(sock)
    if error is None:
        if packet[0] != 1:
            raise AssertionError(f"expected success, got {packet.hex()}")
        return
    if packet[0] != 0 or packet[1] != error:
        raise AssertionError(f"expected error {error}, got {packet.hex()}")
    actual_bad_value = struct.unpack_from("<I", packet, 4)[0]
    if actual_bad_value != bad_value:
        raise AssertionError(f"expected bad value 0x{bad_value:x}, got 0x{actual_bad_value:x}")
    sync = read_packet(sock)
    if sync[0] != 1:
        raise AssertionError(f"stream did not recover after error: {sync.hex()}")


def run(host, port):
    with socket.create_connection((host, port), timeout=3) as sock:
        sock.settimeout(3)
        setup(sock)
        major = query_extension(sock, "XKEYBOARD")
        sock.sendall(request(major, 0, struct.pack("<HH", 1, 0)))
        use_extension = read_packet(sock)
        if use_extension[0] != 1:
            raise AssertionError(f"UseExtension failed: {use_extension.hex()}")

        sock.sendall(get_device_info(major, CORE_POINTER, INDICATORS, ALL_CLASSES, ALL_IDS))
        pointer = read_packet(sock)
        if pointer[0] != 1 or struct.unpack_from("<H", pointer, 14)[0] != 0:
            raise AssertionError(f"core pointer unexpectedly has LED feedback: {pointer.hex()}")
        if struct.unpack_from("<HHH", pointer, 8) != (0, 0x001E, 0):
            raise AssertionError(f"core pointer capability report is inconsistent: {pointer.hex()}")

        expect_void_result(
            sock,
            get_device_info(major, CORE_POINTER, INDICATORS, 0x0700, ALL_IDS),
            BAD_VALUE,
            0x0700,
        )
        expect_void_result(
            sock,
            get_device_info(major, CORE_POINTER, INDICATORS, DEFAULT_CLASS, DEFAULT_ID),
            BAD_MATCH,
        )

        sock.sendall(get_device_info(major, CORE_KEYBOARD, INDICATORS, ALL_CLASSES, ALL_IDS))
        keyboard = read_packet(sock)
        if keyboard[0] != 1:
            raise AssertionError(f"core keyboard inventory failed: {keyboard.hex()}")
        if struct.unpack_from("<H", keyboard, 14)[0] != 1:
            raise AssertionError(f"expected one core keyboard feedback: {keyboard.hex()}")
        if struct.unpack_from("<HH", keyboard, 36) != (KBD_FEEDBACK, 0):
            raise AssertionError(f"unexpected core keyboard feedback identity: {keyboard.hex()}")
        if struct.unpack_from("<I", keyboard, 48)[0] != 0x7FF:
            raise AssertionError(f"unexpected physical indicator mask: {keyboard.hex()}")

        expect_void_result(sock, set_device_info(major, CORE_KEYBOARD, DEFAULT_CLASS, DEFAULT_ID))
        expect_void_result(sock, set_device_info(major, CORE_KEYBOARD, KBD_FEEDBACK, 0))
        expect_void_result(sock, set_device_info(major, CORE_KEYBOARD, KBD_FEEDBACK, 1), BAD_MATCH)
        expect_void_result(sock, set_device_info(major, CORE_KEYBOARD, KBD_FEEDBACK, 7), BAD_MATCH)
        expect_void_result(sock, set_device_info(major, CORE_KEYBOARD, LED_FEEDBACK, 0), BAD_MATCH)
        expect_void_result(sock, set_device_info(major, CORE_POINTER, KBD_FEEDBACK, 0), BAD_MATCH)
        expect_void_result(sock, set_device_info(major, CORE_KEYBOARD, KBD_FEEDBACK, 0x0700), BAD_VALUE, 0x0700)
        expect_void_result(sock, set_device_info(major, CORE_KEYBOARD, 0x0700, DEFAULT_ID), BAD_VALUE, 0x0700)

        indicator_map = bytes((2, 3, 4, 5, 0, 6, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12))
        expect_void_result(
            sock,
            set_device_info(
                major,
                CORE_KEYBOARD,
                KBD_FEEDBACK,
                0,
                change=INDICATOR_MAPS | INDICATOR_STATE,
                state=4,
                maps_present=4,
                phys_indicators=0xFFFFFFFF,
                maps=(indicator_map,),
            ),
        )
        sock.sendall(get_indicator_state(major))
        indicator_state = read_packet(sock)
        if indicator_state[0] != 1 or struct.unpack_from("<I", indicator_state, 8)[0] != 4:
            raise AssertionError(f"DeviceInfo state is not shared with GetIndicatorState: {indicator_state.hex()}")
        sock.sendall(get_indicator_map(major, 4))
        indicator_maps = read_packet(sock)
        if (
            indicator_maps[0] != 1
            or struct.unpack_from("<I", indicator_maps, 8)[0] != 4
            or indicator_maps[16] != 1
            or indicator_maps[32:44] != indicator_map
        ):
            raise AssertionError(f"DeviceInfo map is not shared with GetIndicatorMap: {indicator_maps.hex()}")
        sock.sendall(get_device_info(major, CORE_KEYBOARD, INDICATORS, ALL_CLASSES, ALL_IDS))
        shared = read_packet(sock)
        if (
            struct.unpack_from("<H", shared, 10)[0] != 0x001E
            or struct.unpack_from("<I", shared, 44)[0] != 4
            or struct.unpack_from("<I", shared, 48)[0] != 0x7FF
            or struct.unpack_from("<I", shared, 52)[0] != 4
            or shared[56:68] != indicator_map
        ):
            raise AssertionError(f"shared DeviceInfo feedback is inconsistent: {shared.hex()}")

        action = bytes((1, 0, 0, 0, 0, 0, 0, 0))
        atomic_request = set_device_info(
            major,
            CORE_POINTER,
            DEFAULT_CLASS,
            DEFAULT_ID,
            change=BUTTON_ACTIONS | INDICATOR_STATE,
            first_button=1,
            button_actions=(action,),
        )
        expect_void_result(sock, atomic_request, BAD_MATCH)
        sock.sendall(get_device_info(major, CORE_POINTER, BUTTON_ACTIONS, first_button=1, button_count=1))
        buttons = read_packet(sock)
        if buttons[0] != 1 or buttons[36:44] != bytes(8):
            raise AssertionError(f"Match failure partially changed button action: {buttons.hex()}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    run(args.host, args.port)
    print("PASS xkb_device_info_protocol_test")


if __name__ == "__main__":
    main()
