#!/usr/bin/env python3
import argparse
import json
import socket
import struct


OP_SRC = 1
OP_OVER = 3
REPEAT_PAD = 3


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
    body = read_exact(sock, struct.unpack_from("<H", prefix, 6)[0] * 4)
    resource_base, resource_mask = struct.unpack_from("<II", body, 4)
    vendor_length = struct.unpack_from("<H", body, 16)[0]
    format_count = body[21]
    image_byte_order = body[22]
    if image_byte_order != 0:
        raise AssertionError(f"only LSBFirst image servers are supported, got {image_byte_order}")
    screen_offset = 32 + ((vendor_length + 3) & -4) + format_count * 8
    root = struct.unpack_from("<I", body, screen_offset)[0]
    return resource_base, resource_mask, root


class ResourceIds:
    def __init__(self, base, mask):
        self.base = base
        self.bits = [1 << bit for bit in range(32) if mask & (1 << bit)]
        self.next = 1

    def allocate(self):
        value = self.base
        encoded = self.next
        for index, bit in enumerate(self.bits):
            if encoded & (1 << index):
                value |= bit
        self.next += 1
        return value


def query_extension(sock, name):
    encoded = name.encode("ascii")
    body = struct.pack("<H2x", len(encoded)) + encoded
    body += b"\0" * ((-len(body)) % 4)
    sock.sendall(request(98, 0, body))
    reply = read_packet(sock)
    if reply[0] != 1 or reply[8] == 0:
        raise AssertionError(f"{name} is unavailable: {reply.hex()}")
    return reply[9]


def query_formats(sock, render_opcode):
    sock.sendall(request(render_opcode, 1))
    reply = read_packet(sock)
    if reply[0] != 1:
        raise AssertionError(f"QueryPictFormats failed: {reply.hex()}")
    count = struct.unpack_from("<I", reply, 8)[0]
    formats = []
    for index in range(count):
        offset = 32 + index * 28
        formats.append(
            {
                "id": struct.unpack_from("<I", reply, offset)[0],
                "type": reply[offset + 4],
                "depth": reply[offset + 5],
                "red": struct.unpack_from("<HH", reply, offset + 8),
                "green": struct.unpack_from("<HH", reply, offset + 12),
                "blue": struct.unpack_from("<HH", reply, offset + 16),
                "alpha": struct.unpack_from("<HH", reply, offset + 20),
            }
        )
    argb32 = next(
        item["id"]
        for item in formats
        if item["type"] == 1
        and item["depth"] == 32
        and item["red"] == (16, 0xFF)
        and item["green"] == (8, 0xFF)
        and item["blue"] == (0, 0xFF)
        and item["alpha"] == (24, 0xFF)
    )
    rgb24 = next(
        item["id"]
        for item in formats
        if item["type"] == 1
        and item["depth"] == 24
        and item["red"] == (16, 0xFF)
        and item["green"] == (8, 0xFF)
        and item["blue"] == (0, 0xFF)
        and item["alpha"][1] == 0
    )
    a8 = next(
        item["id"]
        for item in formats
        if item["type"] == 1
        and item["depth"] == 8
        and item["red"][1] == 0
        and item["green"][1] == 0
        and item["blue"][1] == 0
        and item["alpha"] == (0, 0xFF)
    )
    return argb32, rgb24, a8


def create_pixmap(depth, pixmap, root, width, height):
    return request(53, depth, struct.pack("<IIHH", pixmap, root, width, height))


def create_gc(gc, drawable):
    return request(55, 0, struct.pack("<III", gc, drawable, 0))


def put_image32(drawable, gc, width, height, pixels):
    if len(pixels) != width * height:
        raise AssertionError("pixel count does not match image dimensions")
    body = struct.pack("<IIHHhhBB2x", drawable, gc, width, height, 0, 0, 0, 32)
    body += b"".join(struct.pack("<I", pixel) for pixel in pixels)
    return request(72, 2, body)


def put_image8(drawable, gc, alphas):
    stride = (len(alphas) + 3) & -4
    data = bytes(alphas) + bytes(stride - len(alphas))
    body = struct.pack("<IIHHhhBB2x", drawable, gc, len(alphas), 1, 0, 0, 0, 8) + data
    return request(72, 2, body)


def create_picture(render_opcode, picture, drawable, format_id):
    return request(render_opcode, 4, struct.pack("<IIII", picture, drawable, format_id, 0))


def change_picture_repeat(render_opcode, picture, repeat):
    return request(render_opcode, 5, struct.pack("<III", picture, 1, repeat))


def fill_rectangles(render_opcode, operation, picture, color, width, height):
    body = struct.pack("<B3xIHHHHhhHH", operation, picture, *color, 0, 0, width, height)
    return request(render_opcode, 26, body)


def create_linear_gradient(render_opcode, picture, p1, p2, colors):
    body = struct.pack("<IiiiiI", picture, p1[0], p1[1], p2[0], p2[1], len(colors))
    body += b"".join(struct.pack("<I", stop) for stop, _ in colors)
    body += b"".join(struct.pack("<HHHH", *color) for _, color in colors)
    return request(render_opcode, 34, body)


def set_picture_transform(render_opcode, picture, transform):
    return request(render_opcode, 28, struct.pack("<I9i", picture, *transform))


def composite(
    render_opcode,
    operation,
    source,
    destination,
    width,
    *,
    height=1,
    mask=0,
    source_x=0,
    source_y=0,
    destination_x=0,
    destination_y=0,
):
    body = bytearray(32)
    body[0] = operation
    struct.pack_into(
        "<IIIhhhhhhHH",
        body,
        4,
        source,
        mask,
        destination,
        source_x,
        source_y,
        0,
        0,
        destination_x,
        destination_y,
        width,
        height,
    )
    return request(render_opcode, 8, body)


def get_image(drawable, width, height=1, *, x=0, y=0):
    return request(73, 2, struct.pack("<IhhHHI", drawable, x, y, width, height, 0xFFFFFFFF))


def read_pixels(sock, width, height=1):
    reply = read_packet(sock)
    if reply[0] != 1:
        raise AssertionError(f"GetImage failed after an earlier request: {reply.hex()}")
    return list(struct.unpack_from(f"<{width * height}I", reply, 32))


def hex_pixels(pixels):
    return [f"0x{pixel:08x}" for pixel in pixels]


def assert_pixels(label, actual, expected):
    if actual != expected:
        raise AssertionError(f"{label}: expected {hex_pixels(expected)}, got {hex_pixels(actual)}")


def assert_rgb24(label, actual, expected):
    assert_pixels(label, [pixel & 0x00FFFFFF for pixel in actual], expected)


def run(host, port, dump):
    with socket.create_connection((host, port), timeout=5) as sock:
        sock.settimeout(5)
        resource_base, resource_mask, root = setup(sock)
        ids = ResourceIds(resource_base, resource_mask)
        render_opcode = query_extension(sock, "RENDER")
        argb32, rgb24, a8 = query_formats(sock, render_opcode)

        source_pixmap = ids.allocate()
        destination_pixmap = ids.allocate()
        mask_pixmap = ids.allocate()
        source_gc = ids.allocate()
        destination_gc = ids.allocate()
        mask_gc = ids.allocate()
        source_picture = ids.allocate()
        destination_picture = ids.allocate()
        mask_picture = ids.allocate()
        gradient_picture = ids.allocate()
        gradient_pixmap = ids.allocate()
        gradient_gc = ids.allocate()
        gradient_destination_picture = ids.allocate()
        fill_pixmap = ids.allocate()
        fill_picture = ids.allocate()
        lifecycle_destination_pixmap = ids.allocate()
        lifecycle_destination_picture = ids.allocate()

        gradient_width = 5
        sources = [0x0F000000, 0x08000000, 0x05000000, 0x02000000, 0x80146E2D]
        destinations = [0xFF191A1C, 0xFF191A1C, 0xFF191A1C, 0xFF191A1C, 0xFFE6322C]
        masks = [0xFF, 0x80, 0x80, 0x40, 0x80]
        width = len(sources)

        sock.sendall(create_pixmap(32, source_pixmap, root, width, 1))
        sock.sendall(create_pixmap(32, destination_pixmap, root, width, 1))
        sock.sendall(create_pixmap(8, mask_pixmap, root, width, 1))
        sock.sendall(create_gc(source_gc, source_pixmap))
        sock.sendall(create_gc(destination_gc, destination_pixmap))
        sock.sendall(create_gc(mask_gc, mask_pixmap))
        sock.sendall(put_image32(source_pixmap, source_gc, width, 1, sources))
        sock.sendall(put_image8(mask_pixmap, mask_gc, masks))
        sock.sendall(create_picture(render_opcode, source_picture, source_pixmap, argb32))
        sock.sendall(create_picture(render_opcode, destination_picture, destination_pixmap, argb32))
        sock.sendall(create_picture(render_opcode, mask_picture, mask_pixmap, a8))

        sock.sendall(create_pixmap(24, fill_pixmap, root, 1, gradient_width + 1))
        sock.sendall(create_picture(render_opcode, fill_picture, fill_pixmap, rgb24))
        sock.sendall(fill_rectangles(
            render_opcode,
            OP_SRC,
            fill_picture,
            (0x19FF, 0x1AFF, 0x1CFF, 0xFFFF),
            1,
            gradient_width + 1,
        ))
        sock.sendall(get_image(fill_pixmap, 1))
        fill_rgb24 = read_pixels(sock, 1)

        sock.sendall(put_image32(destination_pixmap, destination_gc, width, 1, destinations))
        sock.sendall(composite(render_opcode, OP_OVER, source_picture, destination_picture, width))
        sock.sendall(get_image(destination_pixmap, width))
        drawable_over = read_pixels(sock, width)

        sock.sendall(put_image32(destination_pixmap, destination_gc, width, 1, destinations))
        sock.sendall(composite(render_opcode, OP_SRC, source_picture, destination_picture, width, mask=mask_picture))
        sock.sendall(get_image(destination_pixmap, width))
        masked_src = read_pixels(sock, width)

        sock.sendall(create_pixmap(32, gradient_pixmap, root, 256, 256))
        sock.sendall(create_gc(gradient_gc, gradient_pixmap))
        sock.sendall(create_picture(render_opcode, gradient_destination_picture, gradient_pixmap, argb32))
        sock.sendall(create_pixmap(24, lifecycle_destination_pixmap, root, 256, gradient_width + 1))
        sock.sendall(create_picture(
            render_opcode,
            lifecycle_destination_picture,
            lifecycle_destination_pixmap,
            rgb24,
        ))
        sock.sendall(create_linear_gradient(
            render_opcode,
            gradient_picture,
            (5 << 16, 108 << 16),
            (5 << 16, 113 << 16),
            [(0, (0x00FF, 0x00FF, 0x00FF, 0x10FF)), (1 << 16, (0x00FF, 0x00FF, 0x00FF, 0x0000))],
        ))
        sock.sendall(change_picture_repeat(render_opcode, gradient_picture, REPEAT_PAD))
        sock.sendall(set_picture_transform(
            render_opcode,
            gradient_picture,
            (1 << 16, 0, -(842 << 16), 0, 1 << 16, -(719 << 16), 0, 0, 1 << 16),
        ))
        sock.sendall(put_image32(gradient_pixmap, gradient_gc, 1, gradient_width, destinations))
        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            gradient_picture,
            gradient_destination_picture,
            1,
            height=gradient_width,
            source_x=847,
            source_y=827,
        ))
        sock.sendall(get_image(gradient_pixmap, 1, gradient_width))
        gradient = read_pixels(sock, 1, gradient_width)

        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            gradient_destination_picture,
            fill_picture,
            1,
            height=gradient_width,
        ))
        sock.sendall(get_image(fill_pixmap, 1, gradient_width))
        drawable_rgb24_over = read_pixels(sock, 1, gradient_width)
        fixed_row_unshifted = [drawable_rgb24_over[3]]

        sock.sendall(fill_rectangles(
            render_opcode,
            OP_SRC,
            fill_picture,
            (0x19FF, 0x1AFF, 0x1CFF, 0xFFFF),
            1,
            gradient_width + 1,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            gradient_destination_picture,
            fill_picture,
            1,
            height=gradient_width,
            destination_y=1,
        ))
        sock.sendall(get_image(fill_pixmap, 1, y=3))
        shifted_drawable_rgb24_over = read_pixels(sock, 1)

        sock.sendall(put_image32(gradient_pixmap, gradient_gc, 1, gradient_width, [0xFF191A1C] * gradient_width))
        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            gradient_picture,
            gradient_destination_picture,
            1,
            height=gradient_width,
            source_x=847,
            source_y=827,
        ))
        sock.sendall(get_image(gradient_pixmap, 1, gradient_width))
        gradient_over = read_pixels(sock, 1, gradient_width)

        sock.sendall(fill_rectangles(
            render_opcode,
            OP_SRC,
            fill_picture,
            (0x19FF, 0x1AFF, 0x1CFF, 0xFFFF),
            1,
            gradient_width,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            gradient_picture,
            fill_picture,
            1,
            height=gradient_width,
            source_x=847,
            source_y=827,
        ))
        sock.sendall(get_image(fill_pixmap, 1, gradient_width))
        rgb24_gradient_over = read_pixels(sock, 1, gradient_width)

        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            gradient_picture,
            gradient_destination_picture,
            256,
            height=gradient_width,
            source_x=847,
            source_y=827,
        ))
        sock.sendall(get_image(gradient_pixmap, 1, gradient_width))
        scratch_band_left_edge = read_pixels(sock, 1, gradient_width)
        sock.sendall(get_image(gradient_pixmap, 1, gradient_width, x=255))
        scratch_band_right_edge = read_pixels(sock, 1, gradient_width)

        sock.sendall(fill_rectangles(
            render_opcode,
            OP_SRC,
            lifecycle_destination_picture,
            (0x19FF, 0x1AFF, 0x1CFF, 0xFFFF),
            256,
            gradient_width + 1,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            gradient_destination_picture,
            lifecycle_destination_picture,
            256,
            height=gradient_width,
        ))
        sock.sendall(get_image(lifecycle_destination_pixmap, 1, x=17, y=3))
        lifecycle_fixed_row_y0 = read_pixels(sock, 1)

        sock.sendall(fill_rectangles(
            render_opcode,
            OP_SRC,
            lifecycle_destination_picture,
            (0x19FF, 0x1AFF, 0x1CFF, 0xFFFF),
            256,
            gradient_width + 1,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            gradient_destination_picture,
            lifecycle_destination_picture,
            256,
            height=gradient_width,
            destination_y=1,
        ))
        sock.sendall(get_image(lifecycle_destination_pixmap, 1, x=17, y=3))
        lifecycle_fixed_row_y1 = read_pixels(sock, 1)

        sock.sendall(composite(
            render_opcode,
            OP_SRC,
            gradient_picture,
            gradient_destination_picture,
            5,
            height=gradient_width,
            source_x=847,
            source_y=828,
        ))
        sock.sendall(get_image(gradient_pixmap, 6, gradient_width))
        scratch_after_5x5_repaint = read_pixels(sock, 6, gradient_width)

        sock.sendall(fill_rectangles(
            render_opcode,
            OP_SRC,
            lifecycle_destination_picture,
            (0x19FF, 0x1AFF, 0x1CFF, 0xFFFF),
            256,
            gradient_width + 1,
        ))
        sock.sendall(composite(
            render_opcode,
            OP_OVER,
            gradient_destination_picture,
            lifecycle_destination_picture,
            5,
            height=gradient_width,
            destination_y=1,
        ))
        sock.sendall(get_image(lifecycle_destination_pixmap, 6, x=0, y=3))
        lifecycle_5x5_fixed_row = read_pixels(sock, 6)

        results = {
            "drawable_over": hex_pixels(drawable_over),
            "drawable_rgb24_over": hex_pixels(drawable_rgb24_over),
            "fill_rgb24": hex_pixels(fill_rgb24),
            "fixed_row_unshifted": hex_pixels(fixed_row_unshifted),
            "masked_src": hex_pixels(masked_src),
            "gradient": hex_pixels(gradient),
            "gradient_over": hex_pixels(gradient_over),
            "lifecycle_5x5_fixed_row": hex_pixels(lifecycle_5x5_fixed_row),
            "lifecycle_fixed_row_y0": hex_pixels(lifecycle_fixed_row_y0),
            "lifecycle_fixed_row_y1": hex_pixels(lifecycle_fixed_row_y1),
            "rgb24_gradient_over": hex_pixels(rgb24_gradient_over),
            "scratch_after_5x5_repaint": hex_pixels(scratch_after_5x5_repaint),
            "scratch_band_edges": hex_pixels(scratch_band_left_edge + scratch_band_right_edge),
            "shifted_drawable_rgb24_over": hex_pixels(shifted_drawable_rgb24_over),
        }
        if dump:
            print(json.dumps(results, sort_keys=True))
            return

        assert_pixels("premultiplied drawable OpOver", drawable_over, [
            0xFF18181A, 0xFF18191B, 0xFF19191B, 0xFF191A1C, 0xFF878743,
        ])
        assert_rgb24("notification RGB24 FillRectangles", fill_rgb24, [0x00191A1C])
        assert_pixels("premultiplied drawable masked OpSrc", masked_src, [
            0x0F000000, 0x04000000, 0x03000000, 0x01000000, 0x400A3717,
        ])
        assert_pixels("transformed notification gradient OpSrc", gradient, [
            0x0F000000, 0x0C000000, 0x08000000, 0x05000000, 0x02000000,
        ])
        assert_rgb24("stored notification gradient drawable OpOver on RGB24", drawable_rgb24_over, [
            0x0018181A, 0x0018191B, 0x0018191B, 0x0019191B, 0x00191A1C,
        ])
        assert_rgb24("unshifted stored gradient at fixed destination row", fixed_row_unshifted, [
            0x0019191B,
        ])
        assert_rgb24("shifted stored gradient at fixed destination row", shifted_drawable_rgb24_over, [
            0x0018191B,
        ])
        assert_pixels("transformed notification gradient OpOver", gradient_over, [
            0xFF18181A, 0xFF18191B, 0xFF18191B, 0xFF19191B, 0xFF191A1C,
        ])
        assert_rgb24("transformed notification gradient OpOver on RGB24", rgb24_gradient_over, [
            0x0018181A, 0x0018191B, 0x0018191B, 0x0019191B, 0x00191A1C,
        ])
        assert_pixels("reusable ARGB32 scratch 256x5 edge columns", scratch_band_left_edge + scratch_band_right_edge, [
            0x0F000000, 0x0C000000, 0x08000000, 0x05000000, 0x02000000,
            0x0F000000, 0x0C000000, 0x08000000, 0x05000000, 0x02000000,
        ])
        assert_rgb24("reusable scratch at fixed row with destination y=0", lifecycle_fixed_row_y0, [
            0x0019191B,
        ])
        assert_rgb24("reusable scratch at fixed row with destination y=1", lifecycle_fixed_row_y1, [
            0x0018191B,
        ])
        assert_pixels("reusable ARGB32 scratch after 5x5 origin repaint", scratch_after_5x5_repaint, [
            0x0C000000, 0x0C000000, 0x0C000000, 0x0C000000, 0x0C000000, 0x0F000000,
            0x08000000, 0x08000000, 0x08000000, 0x08000000, 0x08000000, 0x0C000000,
            0x05000000, 0x05000000, 0x05000000, 0x05000000, 0x05000000, 0x08000000,
            0x02000000, 0x02000000, 0x02000000, 0x02000000, 0x02000000, 0x05000000,
            0x02000000, 0x02000000, 0x02000000, 0x02000000, 0x02000000, 0x02000000,
        ])
        assert_rgb24("reused 5x5 scratch OpOver boundary", lifecycle_5x5_fixed_row, [
            0x0019191B, 0x0019191B, 0x0019191B, 0x0019191B, 0x0019191B, 0x00191A1C,
        ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--dump", action="store_true")
    args = parser.parse_args()
    run(args.host, args.port, args.dump)
    if not args.dump:
        print("PASS xrender_low_alpha_protocol_test")


if __name__ == "__main__":
    main()
