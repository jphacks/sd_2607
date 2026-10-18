"""Bounded, no-reset serial observation + existing Android UI handoff for BLE1328.

Does not create posts, extract app storage, enable networking, or flash/reset ESP32.
Requires the already-created test post, pyserial, adb, and explicit COM3 ownership.
"""
import argparse
import datetime
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import serial

ADB = "C:/Users/chiba/AppData/Local/Android/Sdk/platform-tools/adb.exe"
TARGET = "77588a3c"
OUTPUT = Path(__file__).with_name("hardware-BLE1328-serial.json")


def adb(*args):
    return subprocess.check_output([ADB, "-s", "T4AIOC732121JRB", *args], timeout=15).decode("utf-8", "replace")


def click(text):
    adb("shell", "uiautomator", "dump", "/sdcard/mndt-audit-ui.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/mndt-audit-ui.xml"))
    matches = [n for n in root.iter("node") if n.get("text") == text]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one UI control: {text}")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0].get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", default=TARGET)
    parser.add_argument("--source", choices=("demo", "peer"), default="demo")
    parser.add_argument("--output", default=OUTPUT.name)
    parser.add_argument("--apply-peer-filter", action="store_true", help="Send current-node UART peers command; no reset")
    args = parser.parse_args()
    target = args.target
    if not re.fullmatch("[0-9a-f]{8,64}", target):
        parser.error("target must be 8-64 lowercase hex")
    source = "org.mndt.demo" + (".peer" if args.source == "peer" else "")
    receiver = "org.mndt.demo" + (".peer" if args.source == "demo" else "")
    output_path = Path(__file__).with_name(args.output)
    if output_path.exists():
        raise RuntimeError("Refusing to overwrite evidence")
    records = []

    def record(kind, line):
        item = {"at": datetime.datetime.now().astimezone().isoformat(), "kind": kind, "line": line}
        records.append(item)
        print(json.dumps(item, ensure_ascii=False), flush=True)

    port = serial.Serial(port=None, baudrate=115200, timeout=0.1)
    port.dtr = False
    port.rts = False
    port.port = "COM3"
    phase = "source"
    saved = set()
    source_stopped_at = None
    success = False
    try:
        port.open()  # DTR/RTS are configured before open; no reset/provisioning operation.
        record("action", "COM3 opened without asserting DTR/RTS")
        if args.apply_peer_filter:
            nodes = json.loads(Path(__file__).with_name("hardware-current-nodes.json").read_text())["nodes"]
            for package, identity in nodes.items():
                if adb("shell", "pidof", package).strip() != identity["pid"]:
                    raise RuntimeError("Node ledger process changed")
                if not re.fullmatch("[0-9a-f]{16}", identity["node"]):
                    raise RuntimeError("Invalid allowlist node")
            command = "peers " + " ".join(identity["node"] for identity in nodes.values()) + "\n"
            port.write(command.encode("ascii"))
            port.flush()
            record("action", "UART " + command.strip())
        adb("shell", "input", "swipe", "530", "500", "530", "2200", "400")
        click("中継開始")
        record("action", source + " relay started through UI; online policy remains disabled")
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            line = port.readline().decode("utf-8", "replace").strip()
            if line:
                if "rst:" in line or "ESP-ROM:" in line:
                    raise RuntimeError("Unexpected ESP32 reboot detected; handoff invalid")
                if any(s in line for s in ("mesh:", "BLE", "TTS", "Message preview", "alive")):
                    record("serial", line)
                match = re.search(r"mesh: saved ([0-9a-f]+)", line)
                if phase == "source" and match:
                    saved.add(match[1])
                # Source has exactly one post and one separate genesis. Preserve both before disconnect.
                if phase == "source" and any(i.startswith(target[:8]) for i in saved) and len(saved) >= 2:
                    adb("shell", "am", "force-stop", source)
                    source_stopped_at = time.monotonic()
                    phase = "disconnect"
                    record("action", source + " force-stopped immediately after post and second save; confirm control ID against Android log")
                if phase == "peer" and "mesh: peer accepted " + target[:8] in line:
                    success = True
                    record("action", "ESP32 observed Peer custody ACK for target post")
                    # Briefly let the separate genesis finish; then close the service to stop cache churn.
                    deadline = min(deadline, time.monotonic() + 10)
            if phase == "disconnect" and time.monotonic() - source_stopped_at >= 2:
                adb("shell", "am", "start", "-n", receiver + "/org.mndt.app.MainActivity")
                click("中継開始")
                phase = "peer"
                record("action", receiver + " relay started through UI after source stopped")
        if not success:
            raise RuntimeError("No target Peer custody ACK within 100 seconds")
    finally:
        adb("shell", "am", "force-stop", "org.mndt.demo")
        adb("shell", "am", "force-stop", "org.mndt.demo.peer")
        if port.is_open:
            port.close()
        record("action", "Both apps force-stopped; COM3 closed; radio settings unchanged")
        with output_path.open("x", encoding="utf-8") as output:
            json.dump({"targetPrefix": target, "source": source, "receiver": receiver, "peerAckObserved": success, "records": records}, output, ensure_ascii=False, indent=2)
            output.write("\n")


if __name__ == "__main__":
    main()
