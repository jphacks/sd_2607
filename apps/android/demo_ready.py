"""Leave the authorized normal demo running: offline OPPO Peer -> online ASUS Demo.
No posts, COM3, wipes, or final stop/offline restoration.
"""
import datetime
import json
from pathlib import Path
from clean_pair import adb, click, seek, top, ui, network, radios

ASUS = "T4AIOC732121JRB"
OPPO = "4f4ebc6f"

adb(ASUS, "shell", "am", "force-stop", "org.mndt.demo.peer")
adb(OPPO, "shell", "am", "force-stop", "org.mndt.demo")
adb(OPPO, "shell", "svc", "wifi", "disable")
adb(OPPO, "shell", "svc", "data", "disable")
report = {"at": datetime.datetime.now().astimezone().isoformat(), "mode": "WORKING DEMO requested: leave relays ON; do not restore offline/stopped"}
apps = {}
for serial, package, export in ((OPPO, "org.mndt.demo.peer", False), (ASUS, "org.mndt.demo", True)):
    adb(serial, "shell", "am", "start", "-n", package + "/org.mndt.app.MainActivity")
    control = seek(serial, lambda t: t in ("オンライン搬出を再開", "オンライン搬出を停止"))
    if export and control.get("text") == "オンライン搬出を再開":
        click(serial, "オンライン搬出を再開")
    if not export and control.get("text") == "オンライン搬出を停止":
        click(serial, "オンライン搬出を停止")
    seek(serial, lambda t: t == ("オンライン搬出を停止" if export else "オンライン搬出を再開"))
    top(serial)
    click(serial, "中継開始")
    seek(serial, lambda t: t.startswith("中継中"))
    apps[serial] = {"package": package, "relay": "ON", "export": "ON" if export else "OFF",
                    "pid": adb(serial, "shell", "pidof", package).strip(), "network": network(serial), "radio": radios(serial)}
    print(json.dumps(apps[serial], ensure_ascii=False), flush=True)
report["apps"] = apps
report["otherApps"] = "ASUS Peer and existing OPPO Demo force-stopped; old OPPO Demo not uninstalled/updated"
Path(__file__).with_name("hardware-working-demo-state.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False), flush=True)
