#!/usr/bin/env python3
import argparse
import struct

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
    get_image,
    put_image32,
    query_extension,
    query_formats,
    read_pixels,
    setup,
)
import socket


REPEAT_NORMAL = 1
REPEAT_REFLECT = 3


def div_255(value):
    biased = value + 0x80
    return (biased + (biased >> 8)) >> 8


def channel(pixel, shift):
    return (pixel >> shift) & 0xFF


def over(source, destination):
    inverse_alpha = 255 - channel(source, 24)
    output = channel(source, 24) + div_255(channel(destination, 24) * inverse_alpha)
    for shift in (16, 8, 0):
        output = (output << 8) | min(
            255,
            channel(source, shift) + div_255(channel(destination, shift) * inverse_alpha),
        )
    return output


def f32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def fadd(left, right):
    return f32(f32(left) + f32(right))


def fsub(left, right):
    return f32(f32(left) - f32(right))


def fmul(left, right):
    return f32(f32(left) * f32(right))


def fdiv(left, right):
    return f32(f32(left) / f32(right))


def pixman_channel(raw_start, raw_end, fixed_position):
    left = fmul(raw_start, fdiv(1.0, 257.0))
    right = fmul(raw_end, fdiv(1.0, 257.0))
    reciprocal = fdiv(1.0, fsub(1.0, 0.0))
    bias = fmul(fmul(fsub(fmul(left, 1.0), fmul(right, 0.0)), reciprocal), fdiv(1.0, 255.0))
    slope = fmul(fmul(fsub(right, left), reciprocal), fdiv(1.0, 255.0))
    position = fmul(fixed_position, fdiv(1.0, 65536.0))
    value = fmul(255.0, fadd(fmul(slope, position), bias))
    return max(0, min(255, int(fadd(value, 0.5))))


def awt_gradient_pixel(x, y):
    fixed = 1 << 16
    dx = 276 * fixed
    dy = 55 * fixed
    length = dx * dx + dy * dy
    sample_x = 24 * fixed + fixed // 2
    sample_y = (205 + y) * fixed + fixed // 2
    origin_projection = dx * (24 * fixed) + dy * (205 * fixed)
    numerator = dx * sample_x + dy * sample_y - origin_projection
    inverse_denominator = fixed * float(fixed) / float(length * fixed)
    first_position = int(numerator * inverse_denominator)
    increment = (dx * fixed) * inverse_denominator
    position = max(0, min(fixed, first_position + int(increment * x)))
    red = pixman_channel(0xFFFF, 0x28FF, position)
    green = pixman_channel(0xD2FF, 0xA0FF, position)
    blue = pixman_channel(0x28FF, 0xFFFF, position)
    return 0xFF000000 | (red << 16) | (green << 8) | blue


def run(host, port):
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.settimeout(5)
        resource_base, resource_mask, root = setup(sock)
        ids = ResourceIds(resource_base, resource_mask)
        render_opcode = query_extension(sock, "RENDER")
        argb32, _, _ = query_formats(sock, render_opcode)

        spread_pixmap = ids.allocate()
        spread_destination = ids.allocate()
        spread_gradient = ids.allocate()
        sock.sendall(create_pixmap(32, spread_pixmap, root, 4, 1))
        sock.sendall(create_picture(render_opcode, spread_destination, spread_pixmap, argb32))
        sock.sendall(create_linear_gradient(
            render_opcode,
            spread_gradient,
            (0, 0),
            (10 << 16, 0),
            [
                (0, (0xFFFF, 0, 0, 0xFFFF)),
                (1 << 16, (0, 0, 0xFFFF, 0xFFFF)),
            ],
        ))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            spread_gradient,
            spread_destination,
            1,
            source_x=12,
        ))
        for destination_x, repeat in enumerate((REPEAT_PAD, REPEAT_NORMAL, REPEAT_REFLECT), start=1):
            sock.sendall(change_picture_repeat(render_opcode, spread_gradient, repeat))
            sock.sendall(composite(
                render_opcode,
                OP_SRC,
                spread_gradient,
                spread_destination,
                1,
                source_x=12,
                destination_x=destination_x,
            ))
        sock.sendall(get_image(spread_pixmap, 4))
        assert_pixels("linear gradient spread modes", read_pixels(sock, 4), [
            0x00000000,
            0xFF0000FF,
            0xFFBF0040,
            0xFF4000BF,
        ])

        destination_colors = [0xFF26282C, 0xFF27292F, 0xFF282B32, 0xFF292C36]
        straight_colors = [(0x21, 0x23, 0x26), (0x11, 0x12, 0x13), (0x38, 0x3A, 0x40)]
        cases = []
        for destination in destination_colors:
            for red, green, blue in straight_colors:
                for alpha in range(256):
                    source = alpha << 24
                    source |= ((red * alpha + 127) // 255) << 16
                    source |= ((green * alpha + 127) // 255) << 8
                    source |= (blue * alpha + 127) // 255
                    cases.append((source, destination))

        source_pixmap = ids.allocate()
        destination_pixmap = ids.allocate()
        source_gc = ids.allocate()
        destination_gc = ids.allocate()
        source_picture = ids.allocate()
        destination_picture = ids.allocate()
        sock.sendall(create_pixmap(32, source_pixmap, root, len(cases), 1))
        sock.sendall(create_pixmap(32, destination_pixmap, root, len(cases), 1))
        sock.sendall(create_gc(source_gc, source_pixmap))
        sock.sendall(create_gc(destination_gc, destination_pixmap))
        sock.sendall(put_image32(source_pixmap, source_gc, len(cases), 1, [case[0] for case in cases]))
        sock.sendall(put_image32(destination_pixmap, destination_gc, len(cases), 1, [case[1] for case in cases]))
        sock.sendall(create_picture(render_opcode, source_picture, source_pixmap, argb32))
        sock.sendall(create_picture(render_opcode, destination_picture, destination_pixmap, argb32))
        sock.sendall(composite(render_opcode, OP_OVER, source_picture, destination_picture, len(cases)))
        sock.sendall(get_image(destination_pixmap, len(cases)))
        assert_pixels("3,072 premultiplied OpOver cases", read_pixels(sock, len(cases)), [
            over(source, destination) for source, destination in cases
        ])

        precision_pixmap = ids.allocate()
        precision_destination = ids.allocate()
        precision_gradient = ids.allocate()
        gradient_height = 34
        sock.sendall(create_pixmap(32, precision_pixmap, root, 1, gradient_height))
        sock.sendall(create_picture(render_opcode, precision_destination, precision_pixmap, argb32))
        sock.sendall(create_linear_gradient(
            render_opcode,
            precision_gradient,
            (0, 0),
            (0, 300 << 16),
            [
                (0, (0x26FF, 0x28FF, 0x2CFF, 0)),
                (1 << 16, (0x26FF, 0x28FF, 0x2CFF, 0xFFFF)),
            ],
        ))
        sock.sendall(change_picture_repeat(render_opcode, precision_gradient, REPEAT_PAD))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            precision_gradient,
            precision_destination,
            1,
            height=gradient_height,
        ))
        sock.sendall(get_image(precision_pixmap, 1, gradient_height))
        precision_expected = []
        for y in range(gradient_height):
            alpha_16 = 0xFFFF * ((y + 0.5) / 300.0)
            alpha = int(alpha_16 / 257.0 + 0.5)
            red = int((0x26FF * alpha_16 / 0xFFFF) / 257.0 + 0.5)
            green = int((0x28FF * alpha_16 / 0xFFFF) / 257.0 + 0.5)
            blue = int((0x2CFF * alpha_16 / 0xFFFF) / 257.0 + 0.5)
            precision_expected.append((alpha << 24) | (red << 16) | (green << 8) | blue)
        assert_pixels(
            "34-row 16-bit gradient premultiplication",
            read_pixels(sock, 1, gradient_height),
            precision_expected,
        )

        awt_width = 304
        awt_height = 34
        awt_pixmap = ids.allocate()
        awt_destination = ids.allocate()
        awt_gradient = ids.allocate()
        sock.sendall(create_pixmap(32, awt_pixmap, root, awt_width, awt_height))
        sock.sendall(create_picture(render_opcode, awt_destination, awt_pixmap, argb32))
        sock.sendall(create_linear_gradient(
            render_opcode,
            awt_gradient,
            (24 << 16, 205 << 16),
            (300 << 16, 260 << 16),
            [
                (0, (0xFFFF, 0xD2FF, 0x28FF, 0xFFFF)),
                (1 << 16, (0x28FF, 0xA0FF, 0xFFFF, 0xFFFF)),
            ],
        ))
        sock.sendall(change_picture_repeat(render_opcode, awt_gradient, REPEAT_PAD))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            awt_gradient,
            awt_destination,
            awt_width,
            height=awt_height,
            source_x=24,
            source_y=205,
        ))
        sock.sendall(get_image(awt_pixmap, awt_width, awt_height))
        assert_pixels(
            "10,336-pixel AWT linear gradient",
            read_pixels(sock, awt_width, awt_height),
            [awt_gradient_pixel(x, y) for y in range(awt_height) for x in range(awt_width)],
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    run(args.host, args.port)
    print("PASS xrender_precision_protocol_test")


if __name__ == "__main__":
    main()
