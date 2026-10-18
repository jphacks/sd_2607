"""Existing-event, two-physical-phone BLE observation. No posts or COM3 operations."""
import datetime
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = "C:/Users/chiba/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SOURCE = "T4AIOC732121JRB"
GATEWAY = "4f4ebc6f"
PACKAGE = "org.mndt.demo.peer"
TARGET = "f44162129aa31843a58af01310d78b61fba6505d23aeed2590ad1862ac2f4c8f"
OUT = Path(__file__).with_name("hardware-two-phone-f441.json")


def adb(serial, *args):
    return subprocess.check_output([ADB, "-s", serial, *args], timeout=12).decode("utf-8", "replace")


def texts(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/mndt-two-phone-ui.xml")
    return ET.fromstring(adb(serial, "shell", "cat", "/sdcard/mndt-two-phone-ui.xml"))


def click(serial, text):
    root = texts(serial)
    found = [n for n in root.iter("node") if n.get("text") == text]
    assert len(found) == 1, text
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", found[0].get("bounds")))
    adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def main():
    assert not OUT.exists()
    baseline = {s: {k: adb(s, "shell", "settings", "get", "global", k).strip()
                    for k in ("airplane_mode_on", "wifi_on", "bluetooth_on", "mobile_data")}
                for s in (SOURCE, GATEWAY)}
    report = {"startedAt": datetime.datetime.now().astimezone().isoformat(), "target": TARGET,
              "baseline": baseline, "newPosts": 0, "directBleReceived": False,
              "publicationWasAlreadyPresent": True, "gatewayLog": []}
    try:
        assert baseline[SOURCE]["wifi_on"] == "0" and baseline[SOURCE]["airplane_mode_on"] == "1"
        adb(GATEWAY, "shell", "am", "start", "-n", PACKAGE + "/org.mndt.app.MainActivity")
        click(GATEWAY, "中継開始")
        pid = adb(GATEWAY, "shell", "pidof", PACKAGE).strip()
        # Already-author-filtered gateway; turn on Wi-Fi only if cellular is unavailable.
        adb(GATEWAY, "shell", "svc", "wifi", "enable")
        deadline = min(time.monotonic() + 85, time.monotonic() + max(0, (datetime.datetime.now().replace(hour=14, minute=27, second=30, microsecond=0) - datetime.datetime.now()).total_seconds()))
        while time.monotonic() < deadline:
            time.sleep(2)
            log = adb(GATEWAY, "logcat", "-d", "--pid=" + pid, "-v", "time", "-s", "Exchange:I", "GattLink:I", "*:S")
            selected = [line for line in log.splitlines() if any(t in line for t in ("hello from", TARGET, "advertising node", "c3e92368074212df"))]
            report["gatewayLog"] = selected
            if any("from c3e92368074212df: accepted " + TARGET in line for line in selected):
                report["directBleReceived"] = True
                time.sleep(8)
                break
        print(json.dumps(report, ensure_ascii=False), flush=True)
    finally:
        if baseline[GATEWAY]["wifi_on"] == "0":
            adb(GATEWAY, "shell", "svc", "wifi", "disable")
        for serial in (SOURCE, GATEWAY):
            try:
                adb(serial, "shell", "am", "start", "-n", PACKAGE + "/org.mndt.app.MainActivity")
                click(serial, "中継停止")
                ts = [n.get("text") for n in texts(serial).iter("node")]
                if "オンライン搬出を停止" in ts:
                    click(serial, "オンライン搬出を停止")
            finally:
                adb(serial, "shell", "am", "force-stop", PACKAGE)
        report["finishedAt"] = datetime.datetime.now().astimezone().isoformat()
        report["restored"] = {s: {k: adb(s, "shell", "settings", "get", "global", k).strip() for k in baseline[s]} for s in baseline}
        OUT.open("x", encoding="utf-8").write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        assert report["restored"] == baseline


if __name__ == "__main__":
    main()
