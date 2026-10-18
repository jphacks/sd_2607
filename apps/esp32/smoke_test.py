"""Reset the USB-connected board and check startup and heartbeat logs."""

import argparse
from pathlib import Path
import re
import time

import serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", help="USB serial port, for example COM3")
    parser.add_argument("--mesh", action="store_true", help="also require successful BLE relay startup")
    parser.add_argument("--speech", action="store_true", help="require local evaluation TTS startup")
    parser.add_argument("--received-speech", action="store_true", help="require a newly received post to finish non-silent speech; needs an active peer")
    parser.add_argument("--log", type=Path, help="save captured logs without message bodies")
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

    if args.log:
        args.log.write_text("\n".join(lines) + "\n", encoding="utf-8")
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
    if args.speech or args.received_speech:
        assert "TTS ready evaluation=1 dictionary_bytes=1969776" in lines, "Local evaluation TTS did not start"
    if args.received_speech:
        saved = {m.group(1) for line in lines if (m := re.match(r"mesh: saved ([0-9a-f]{8}) ", line))}
        spoken = [m for line in lines if (m := re.fullmatch(r"TTS done id=([0-9a-f]{64}) samples=(\d+) nonzero=(\d+) chunks=(\d+)", line))]
        assert any(m.group(1)[:8] in saved and all(int(m.group(i)) > 0 for i in (2, 3, 4)) for m in spoken), "No received post completed non-silent speech"
    print("Serial smoke test passed")


if __name__ == "__main__":
    main()
