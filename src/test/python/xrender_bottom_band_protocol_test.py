#!/usr/bin/env python3
import argparse
import socket
import struct
import zlib

from xrender_low_alpha_protocol_test import (
    OP_OVER,
    OP_SRC,
    REPEAT_PAD,
    ResourceIds,
    assert_pixels,
    change_picture_repeat,
    composite,
    create_gc,
    create_linear_gradient,
    create_picture,
    create_pixmap,
    fill_rectangles,
    get_image,
    query_extension,
    query_formats,
    read_pixels,
    request,
    set_picture_transform,
    setup,
)


EXPECTED_CRC32 = 0x54A53753


def put_image8(drawable, gc, width, height, alphas):
    if len(alphas) != width * height:
        raise AssertionError("alpha count does not match image dimensions")
    stride = (width + 3) & -4
    data = b"".join(
        bytes(alphas[y * width:(y + 1) * width]) + bytes(stride - width)
        for y in range(height)
    )
    body = struct.pack("<IIHHhhBB2x", drawable, gc, width, height, 0, 0, 0, 8) + data
    return request(72, 2, body)


def run(host, port, dump):
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.settimeout(5)
        resource_base, resource_mask, root = setup(sock)
        ids = ResourceIds(resource_base, resource_mask)
        render_opcode = query_extension(sock, "RENDER")
        argb32, _, a8 = query_formats(sock, render_opcode)

        band_width = 104
        band_height = 16
        frame_width = 1260
        frame_height = 160
        frame_x = 856
        frame_y = 83

        temporary_pixmap = ids.allocate()
        mask_pixmap = ids.allocate()
        frame_pixmap = ids.allocate()
        mask_gc = ids.allocate()
        temporary_picture = ids.allocate()
        mask_picture = ids.allocate()
        frame_picture = ids.allocate()
        gradient_picture = ids.allocate()
        mask = [
            (0xFF, 0x80, 0x00, 0xFF)[x % 4]
            for _y in range(band_height)
            for x in range(band_width)
        ]

        sock.sendall(create_pixmap(32, temporary_pixmap, root, band_width, band_height))
        sock.sendall(create_pixmap(8, mask_pixmap, root, band_width, band_height))
        sock.sendall(create_pixmap(32, frame_pixmap, root, frame_width, frame_height))
        sock.sendall(create_gc(mask_gc, mask_pixmap))
        sock.sendall(put_image8(mask_pixmap, mask_gc, band_width, band_height, mask))
        sock.sendall(create_picture(render_opcode, temporary_picture, temporary_pixmap, argb32))
        sock.sendall(create_picture(render_opcode, mask_picture, mask_pixmap, a8))
        sock.sendall(create_picture(render_opcode, frame_picture, frame_pixmap, argb32))
        sock.sendall(create_linear_gradient(
            render_opcode,
            gradient_picture,
            (0, 0),
            (band_width << 16, 0),
            [
                (0, (0x9292, 0xB7B7, 0xFFFF, 0xFFFF)),
                (1 << 16, (0x3636, 0x6A6A, 0xCECE, 0xFFFF)),
            ],
        ))
        sock.sendall(change_picture_repeat(render_opcode, gradient_picture, REPEAT_PAD))
        sock.sendall(set_picture_transform(
            render_opcode,
            gradient_picture,
            (1 << 16, 0, -(856 << 16), 0, 1 << 16, -(847 << 16), 0, 0, 1 << 16),
        ))
        sock.sendall(fill_rectangles(
            render_opcode,
            OP_SRC,
            frame_picture,
            (0x1010, 0x1010, 0x1010, 0xFFFF),
            band_width,
            band_height,
            x=frame_x,
            y=frame_y,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            gradient_picture,
            temporary_picture,
            band_width,
            height=band_height,
            source_x=856,
            source_y=847,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            temporary_picture,
            frame_picture,
            band_width,
            height=band_height,
            mask=mask_picture,
            destination_x=frame_x,
            destination_y=frame_y,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            frame_picture,
            temporary_picture,
            band_width,
            height=band_height,
            source_x=frame_x,
            source_y=frame_y,
        ))
        sock.sendall(get_image(temporary_pixmap, band_width, band_height))
        pixels = read_pixels(sock, band_width, band_height)
        payload = b"".join(struct.pack("<I", pixel) for pixel in pixels)
        crc32 = zlib.crc32(payload)
        if dump:
            print(f"pixels={len(pixels)} crc32=0x{crc32:08x}")
            return

        assert_pixels("IntelliJ bottom-band first 16 pixels", pixels[:16], [
            0xFF92B7FF, 0xFF516387, 0xFF101010, 0xFF8FB4FD,
            0xFF8EB4FD, 0xFF4F6286, 0xFF101010, 0xFF8BB1FB,
            0xFF8AB1FB, 0xFF4D6086, 0xFF101010, 0xFF88AEFA,
            0xFF87AEF9, 0xFF4B5F85, 0xFF101010, 0xFF84ACF8,
        ])
        if crc32 != EXPECTED_CRC32:
            raise AssertionError(
                f"IntelliJ bottom-band payload: expected CRC32 0x{EXPECTED_CRC32:08x}, "
                f"got 0x{crc32:08x}"
            )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--dump", action="store_true")
    args = parser.parse_args()
    run(args.host, args.port, args.dump)
    if not args.dump:
        print("PASS xrender_bottom_band_protocol_test")


if __name__ == "__main__":
    main()
