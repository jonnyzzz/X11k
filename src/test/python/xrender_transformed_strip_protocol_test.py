#!/usr/bin/env python3
import argparse
import socket
import struct

from xrender_low_alpha_protocol_test import (
    OP_SRC,
    ResourceIds,
    assert_pixels,
    composite,
    create_gc,
    create_picture,
    create_pixmap,
    get_image,
    put_image32,
    query_extension,
    query_formats,
    read_pixels,
    request,
    setup,
)


REPEAT_NORMAL = 1


def strip_pixel(x, y):
    wrapped_x = x % 600
    wrapped_y = y % 2
    red = max(0x20, 0x30 - wrapped_x // 48)
    green = max(0x28, 0x54 - wrapped_x // 18 - wrapped_y)
    blue = max(0x28, 0x54 - wrapped_x // 16 - wrapped_y)
    return 0xFF000000 | (red << 16) | (green << 8) | blue


def change_picture_repeat(render_opcode, picture, repeat):
    return request(render_opcode, 5, struct.pack("<III", picture, 1, repeat))


def set_picture_filter(render_opcode, picture, name):
    encoded = name.encode("ascii")
    body = struct.pack("<IHH", picture, len(encoded), 0) + encoded
    body += bytes((-len(body)) % 4)
    return request(render_opcode, 30, body)


def set_picture_transform(render_opcode, picture, transform):
    return request(render_opcode, 28, struct.pack("<I9i", picture, *transform))


def run(host, port):
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.settimeout(5)
        resource_base, resource_mask, root = setup(sock)
        ids = ResourceIds(resource_base, resource_mask)
        render_opcode = query_extension(sock, "RENDER")
        argb32, _, _ = query_formats(sock, render_opcode)

        source_pixmap = ids.allocate()
        source_picture = ids.allocate()
        destination_pixmap = ids.allocate()
        destination_picture = ids.allocate()
        gc = ids.allocate()
        source_width = 600
        source_height = 2
        sample_width = 64
        source = [strip_pixel(x, y) for y in range(source_height) for x in range(source_width)]

        sock.sendall(create_pixmap(32, source_pixmap, root, source_width, source_height))
        sock.sendall(create_pixmap(32, destination_pixmap, root, sample_width, 4))
        sock.sendall(create_gc(gc, source_pixmap))
        sock.sendall(put_image32(source_pixmap, gc, source_width, source_height, source))
        sock.sendall(create_picture(render_opcode, source_picture, source_pixmap, argb32))
        sock.sendall(create_picture(render_opcode, destination_picture, destination_pixmap, argb32))
        sock.sendall(change_picture_repeat(render_opcode, source_picture, REPEAT_NORMAL))
        sock.sendall(set_picture_filter(render_opcode, source_picture, "good"))
        sock.sendall(set_picture_transform(
            render_opcode,
            source_picture,
            (1 << 16, 0, -(624 << 16), 0, 1 << 16, 0, 0, 0, 1 << 16),
        ))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            source_picture,
            destination_picture,
            sample_width,
            height=source_height,
            source_x=624,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            source_picture,
            destination_picture,
            sample_width,
            height=source_height,
            source_x=880,
            destination_y=2,
        ))
        sock.sendall(get_image(destination_pixmap, sample_width, 4))

        expected = []
        for source_x in (624, 880):
            for y in range(source_height):
                expected.extend(strip_pixel(source_x - 624 + x, y) for x in range(sample_width))
        assert_pixels(
            "IntelliJ transformed repeated strip",
            read_pixels(sock, sample_width, 4),
            expected,
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    run(args.host, args.port)
    print("PASS xrender_transformed_strip_protocol_test")


if __name__ == "__main__":
    main()
