"""Reset the USB-connected board and check startup and heartbeat logs."""

import argparse
import time

import serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", help="USB serial port, for example COM3")
    parser.add_argument("--mesh", action="store_true", help="also require successful BLE relay startup")
    args = parser.parse_args()

    with serial.Serial(port=None, baudrate=115200, timeout=1) as device:
        device.port = args.port
        device.dtr = False
        device.rts = False
        device.open()
        device.rts = True
        time.sleep(0.1)
        device.rts = False

        deadline = time.monotonic() + 30
        lines = []
        while time.monotonic() < deadline:
            line = device.readline().decode("utf-8", errors="replace").strip()
            if line:
                print(line, flush=True)
                lines.append(line)

    assert sum("ESP32 ready chip=" in line for line in lines) == 1, (
        "Expected exactly one startup log"
    )
    assert sum(line.startswith("alive uptime_ms=") for line in lines) >= 2, (
        "Expected at least two heartbeat logs"
    )
    assert not any(
        "Guru Meditation" in line or "Brownout detector" in line for line in lines
    ), "Device fault detected"
    if args.mesh:
        assert "mesh: BLE relay ready" in lines, "BLE relay did not start"
        assert not any("Relay unavailable" in line for line in lines), "Relay startup failed"
    print("Serial smoke test passed")


if __name__ == "__main__":
    main()
