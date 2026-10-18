"""Bounded UI-only export of existing Peer test event; always restore Wi-Fi OFF.

No post generation, private-store access, ESP32 access, or data deletion.
This is a DIRECT online leg, not proof of ESP32-to-Demo delivery.
"""
import argparse
import datetime
import json
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from hardware_session import adb, click

OUTPUT = Path(__file__).with_name("hardware-f441-direct-online.json")
PREFIX = "f44162129aa3"


def snapshot():
    adb("shell", "uiautomator", "dump", "/sdcard/mndt-audit-ui.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/mndt-audit-ui.xml"))
    texts = [n.get("text", "") for n in root.iter("node") if n.get("text")]
    return texts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--output", default=OUTPUT.name)
    args = parser.parse_args()
    output_path = Path(__file__).with_name(args.output)
    if output_path.exists():
        raise RuntimeError("Refusing to overwrite evidence")
    baseline = {k: adb("shell", "settings", "get", "global", k).strip()
                for k in ("airplane_mode_on", "wifi_on", "bluetooth_on", "mobile_data")}
    assert baseline["wifi_on"] == "0", baseline
    evidence = {"route": "DIRECT Peer online export; BLE chain NOT proved", "targetPrefix": PREFIX,
                "baseline": baseline, "observations": [], "allThreeQueuesEmpty": False}
    try:
        texts = snapshot()
        if not args.resume:
            assert "wss://pds.oriom.dev\n未送信 1 件" in texts, "Unexpected queue; do not export"
            assert "オンライン搬出を再開" in texts, "Expected disabled online policy"
        if "オンライン搬出を再開" in texts:
            click("オンライン搬出を再開")
        adb("shell", "svc", "wifi", "enable")
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            time.sleep(5)
            texts = snapshot()
            public = [t for t in texts if t.startswith(("DID:", "PLC ", "wss://", "オンライン搬出", "ネットワーク", "接続", "リレー未受理", "署名検証済み投稿"))]
            observation = {"at": datetime.datetime.now().astimezone().isoformat(), "publicUi": public}
            evidence["observations"].append(observation)
            print(json.dumps(observation, ensure_ascii=False), flush=True)
            if all(url in texts for url in ("wss://pds.oriom.dev", "wss://relay.damus.io", "wss://nos.lol")):
                evidence["allThreeQueuesEmpty"] = True
                if any(t.startswith("PLC 初期操作登録確認済み") for t in texts):
                    evidence["plcConfirmedUi"] = True
                    break
    finally:
        adb("shell", "svc", "wifi", "disable")
        time.sleep(2)
        adb("shell", "am", "force-stop", "org.mndt.demo.peer")
        evidence["restored"] = {k: adb("shell", "settings", "get", "global", k).strip() for k in baseline}
        with output_path.open("x", encoding="utf-8") as output:
            json.dump(evidence, output, ensure_ascii=False, indent=2)
            output.write("\n")
        print(json.dumps({"allThreeQueuesEmpty": evidence["allThreeQueuesEmpty"], "restored": evidence["restored"]}))
        assert evidence["restored"] == baseline, "Radio settings differ from baseline"


if __name__ == "__main__":
    main()
