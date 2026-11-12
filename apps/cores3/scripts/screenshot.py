"""Drive the CoreS3 debug build over UART and save frames as PNG (bench use only)."""
import argparse
import struct
import time
import zlib

import serial


def png(path, width, height, rgb565):
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            v = (rgb565[(y * width + x) * 2] << 8) | rgb565[(y * width + x) * 2 + 1]
            rows += bytes((((v >> 11) & 31) * 255 // 31, ((v >> 5) & 63) * 255 // 63, (v & 31) * 255 // 31))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(bytes(rows))) + chunk(b"IEND", b""))


def shot(port, path):
    port.reset_input_buffer()
    port.write(b"screenshot\n")
    while True:
        line = port.readline()
        if not line:
            raise SystemExit("no screenshot response")
        if line.startswith(b"SHOT "):
            break
    width, height = map(int, line.split()[1:3])
    frame = port.read(width * height * 2)
    if len(frame) != width * height * 2:
        raise SystemExit("short frame")
    png(path, width, height, frame)
    print(path)


def main():
    parser = argparse.ArgumentParser(
        description="Steps run in order on one connection: 'tap:X,Y', 'cmd:LINE', 'wait:SECONDS', 'shot:OUT.png'.")
    parser.add_argument("port")
    parser.add_argument("steps", nargs="+")
    args = parser.parse_args()
    # Opening the USB Serial/JTAG port usually resets the CoreS3: wait for the relay to
    # boot, or carry on if it was already running and printed nothing new.
    port = serial.Serial(args.port, 115200, timeout=1)
    deadline = time.time() + 8
    booting = False
    while time.time() < deadline:
        line = port.readline()
        booting = booting or b"ESP-ROM" in line
        if b"BLE relay ready" in line or (not booting and not line):
            break
    time.sleep(0.5)
    for step in args.steps:
        kind, _, value = step.partition(":")
        if kind == "tap":
            x, y = value.split(",")
            port.write(f"tap {int(x)} {int(y)}\n".encode())
            time.sleep(0.6)
        elif kind == "cmd":
            port.write(value.encode() + b"\n")
            time.sleep(0.6)
        elif kind == "wait":
            time.sleep(float(value))
        elif kind == "shot":
            shot(port, value)
        else:
            raise SystemExit(f"unknown step {step}")


if __name__ == "__main__":
    main()
