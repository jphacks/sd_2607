"""One-post isolated ASUS -> OPPO BLE experiment. No COM3 or private-store access.

Phases are explicit so an independent PDS-absence check separates receive/upload.
All evidence contains only the approved test text and public IDs/status metadata.
"""
import argparse
import datetime
import json
import re
import subprocess
import time
from pathlib import Path

from two_phone_verify import adb, click as visible_click, texts, SOURCE, GATEWAY, PACKAGE, ADB

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
STATE = HERE / "hardware-clean-pair.json"
SOURCE_NODE = "c047ef246330f71e"
GATEWAY_NODE = "1047f1d5e2cd2f66"
AUTHOR = "3503d6e4193c79c955f57704322b47a92bd7a78d5d8d3be971c6ba65021cc818"


def now():
    return datetime.datetime.now().astimezone().isoformat()


def save(state):
    temp = STATE.with_suffix(".tmp")
    temp.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(STATE)


def radios(serial):
    return {k: adb(serial, "shell", "settings", "get", "global", k).strip()
            for k in ("airplane_mode_on", "wifi_on", "bluetooth_on", "mobile_data")}


def network(serial):
    raw = adb(serial, "shell", "dumpsys", "connectivity")
    agents = [line for line in raw.splitlines() if "NetworkAgentInfo{network{" in line]
    transports = []
    validated = False
    for line in agents:
        match = re.search(r"Transports:\s*(.*?)\s+Capabilities:", line)
        if match:
            transport = match[1].strip()
            transports.append(transport)
            caps = re.search(r"Capabilities:\s*([^\]]+)", line)
            if "VPN" not in transport and caps and re.search(r"\bVALIDATED\b", caps[1]):
                validated = True
    return {"at": now(), "transports": transports, "physicalValidated": validated}


def offline(state):
    r = radios(SOURCE)
    n = network(SOURCE)
    assert r["airplane_mode_on"] == "1" and r["wifi_on"] == "0", r
    assert all("VPN" in t for t in n["transports"]), n
    state.setdefault("sourceOfflineChecks", []).append({"radio": r, **n})
    save(state)


def ui(serial):
    return [n.get("text", "") for n in texts(serial).iter("node") if n.get("text")]


def top(serial):
    for _ in range(3):
        adb(serial, "shell", "input", "swipe", "540", "650", "540", "2050", "250")


def seek(serial, predicate):
    for attempt in range(9):
        root = texts(serial)
        found = [n for n in root.iter("node") if predicate(n.get("text", "")) and n.get("bounds") != "[0,0][0,0]"]
        if found:
            return found[0]
        if attempt == 0:
            top(serial)
        else:
            adb(serial, "shell", "input", "swipe", "540", "1900", "540", "950", "300")
    raise RuntimeError("Required UI control not visible; stop without posting")


def click(serial, text):
    seek(serial, lambda value: value == text)
    visible_click(serial, text)


def card(serial, content):
    root = texts(serial)
    candidates = []
    for node in root.iter("node"):
        values = [n.get("text", "") for n in node.iter("node") if n.get("text")]
        if content in values and any(re.match(r"[0-9a-f]{12} /", t) for t in values):
            candidates.append(values)
    return min(candidates, key=len) if candidates else None


def log(serial, pid):
    return adb(serial, "logcat", "-d", "--pid=" + pid, "-v", "time", "-s", "Exchange:I", "GattLink:I", "*:S")


def launch(serial):
    adb(serial, "shell", "am", "start", "-n", PACKAGE + "/org.mndt.app.MainActivity")


def disable(serial):
    launch(serial)
    top(serial)
    click(serial, "中継停止")
    seek(serial, lambda value: value.startswith("中継停止中"))
    control = seek(serial, lambda value: value in ("オンライン搬出を停止", "オンライン搬出を再開"))
    if control.get("text") == "オンライン搬出を停止":
        click(serial, "オンライン搬出を停止")
    seek(serial, lambda value: value == "オンライン搬出を再開")
    top(serial)


def prepare():
    if STATE.exists():
        state = json.loads(STATE.read_text(encoding="utf-8"))
        assert not state["newPostRequested"] and not state.get("readyAt"), "Do not reset or create another post"
    else:
        state = {"startedAt": now(), "marker": "TEST ONLY - NOT DISASTER INFORMATION CLEAN-" + datetime.datetime.now().strftime("%Y%m%dT%H%M%S"),
                 "baseline": {s: radios(s) for s in (SOURCE, GATEWAY)}, "newPostRequested": False,
                 "sourceNode": SOURCE_NODE, "gatewayNode": GATEWAY_NODE, "author": AUTHOR}
        save(state)
    offline(state)
    for serial, label, apk in ((SOURCE, "source", HERE / ".gradle/isolated-source-peer.apk"),
                                (GATEWAY, "gateway", HERE / "app/build/outputs/apk/peer/debug/app-peer-debug.apk")):
        disable(serial)
        identities = [t for t in ui(serial) if t.startswith("DID:")]
        assert len(identities) == 1
        original = state.setdefault("identityBefore", {}).setdefault(serial, identities[0])
        assert original == identities[0], "Do not replace a changed identity"
        backup = HERE / (".gradle/pre-clean-" + label + "-peer.apk")
        if not backup.exists():
            # Public installed executable only, NEVER noBackupFilesDir / preferences / keys.
            remote = adb(serial, "shell", "pm", "path", PACKAGE).strip().removeprefix("package:")
            subprocess.run([ADB, "-s", serial, "pull", remote, str(backup)], check=True, capture_output=True, timeout=45)
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        result = adb(serial, "install", "-r", str(apk))
        assert "Success" in result, result
        launch(serial)
        top(serial)
        assert identities[0] in ui(serial), "Identity changed; stop without posting"
        seek(serial, lambda value: value == "オンライン搬出を再開")
        top(serial)
        save(state)
    adb(GATEWAY, "shell", "svc", "wifi", "enable")
    for _ in range(15):
        offline(state)
        snapshot = network(GATEWAY)
        if snapshot["physicalValidated"]:
            state["gatewayOnlineBeforePost"] = snapshot
            break
        time.sleep(2)
    else:
        raise RuntimeError("Gateway has no validated physical network; no post created")
    click(SOURCE, "中継開始")
    click(GATEWAY, "中継開始")
    state["pids"] = {s: adb(s, "shell", "pidof", PACKAGE).strip() for s in (SOURCE, GATEWAY)}
    for _ in range(30):
        offline(state)
        source_log = log(SOURCE, state["pids"][SOURCE])
        gateway_log = log(GATEWAY, state["pids"][GATEWAY])
        if "hello from " + GATEWAY_NODE in source_log and "hello from " + SOURCE_NODE in gateway_log:
            assert set(re.findall(r"hello from ([0-9a-f]{16})", source_log)) == {GATEWAY_NODE}
            assert set(re.findall(r"hello from ([0-9a-f]{16})", gateway_log)) == {SOURCE_NODE}
            state["readyAt"] = now()
            state["pairHello"] = [l for l in (source_log + gateway_log).splitlines() if "hello from" in l]
            save(state)
            print(json.dumps({"ready": True, "marker": state["marker"], "pairHello": state["pairHello"]}, ensure_ascii=False))
            return
        time.sleep(2)
    raise RuntimeError("No mutually approved BLE session; no post created")


def publish(state):
    assert state.get("readyAt") and not state["newPostRequested"], "Never create a second post"
    offline(state)
    assert network(GATEWAY)["physicalValidated"]
    for serial in (SOURCE, GATEWAY):
        seek(serial, lambda value: value == "オンライン搬出を再開")
    seek(SOURCE, lambda value: value.startswith("公開する本文"))
    root = texts(SOURCE)
    edits = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
    field = next(n for n in edits if any(c.get("text", "").startswith("公開する本文") for c in n.iter("node")))
    assert not field.get("text"), "Existing draft must not be overwritten"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.get("bounds")))
    adb(SOURCE, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    adb(SOURCE, "shell", "input", "text", state["marker"].replace(" ", "%s"))
    adb(SOURCE, "shell", "input", "keyevent", "4")
    edits = [n for n in texts(SOURCE).iter("node") if n.get("class") == "android.widget.EditText"]
    assert any(n.get("text") == state["marker"] for n in edits), "Draft mismatch: DO NOT publish"
    state["newPostRequested"] = True
    state["publishRequestedAt"] = now()
    save(state)  # Crash-safe guard BEFORE the only publish click.
    click(SOURCE, "署名して保存・配送")
    prefix = None
    for _ in range(5):
        values = card(SOURCE, state["marker"])
        if values:
            prefix = next(re.match(r"([0-9a-f]{12}) /", t)[1] for t in values if re.match(r"[0-9a-f]{12} /", t))
            state["sourcePostUi"] = values
            break
        adb(SOURCE, "shell", "input", "swipe", "530", "2100", "530", "1820", "300")
    assert prefix, "Post click occurred; do not retry creation"
    state["prefix"] = prefix
    save(state)
    for _ in range(45):
        offline(state)
        assert network(GATEWAY)["physicalValidated"]
        observed = log(GATEWAY, state["pids"][GATEWAY])
        match = re.search(r"bundle .* from " + SOURCE_NODE + r": accepted (" + prefix + r"[0-9a-f]{52})", observed)
        if match:
            state["eventId"] = match[1]
            state["receivedAt"] = now()
            state["gatewayReceipt"] = [l for l in observed.splitlines() if match[1] in l]
            assert all("from " + SOURCE_NODE + ":" in l for l in state["gatewayReceipt"] if ": accepted " in l)
            seek(GATEWAY, lambda value: value == "オンライン搬出を再開")
            save(state)
            print(json.dumps({"receivedDirectly": True, "eventId": match[1], "receipt": state["gatewayReceipt"]}, ensure_ascii=False))
            return
        time.sleep(2)
    raise RuntimeError("No direct receipt; one post exists, do not create another")


def upload(state):
    checkpoint = json.loads((HERE / "hardware-clean-before-upload.json").read_text(encoding="utf-8"))
    assert checkpoint["absent"] and checkpoint["eventId"] == state["eventId"]
    offline(state)
    assert network(GATEWAY)["physicalValidated"]
    disable(SOURCE)
    adb(SOURCE, "shell", "am", "force-stop", PACKAGE)
    state["sourceStoppedBeforeUpload"] = now()
    state["uploadAuthorizedAt"] = now()
    save(state)
    click(GATEWAY, "オンライン搬出を再開")
    for _ in range(24):
        offline(state)
        values = card(GATEWAY, state["marker"])
        if values:
            state["gatewayPostUi"] = values
            if any(t.startswith("リレー受理:") and "pds.oriom.dev" in t for t in values):
                state["gatewayPdsOkUiAt"] = now()
                save(state)
                print(json.dumps({"pdsOkFromGateway": True, "eventId": state["eventId"], "ui": values}, ensure_ascii=False))
                return
        adb(GATEWAY, "shell", "input", "swipe", "530", "2100", "530", "1820", "300")
        time.sleep(2)
    save(state)
    raise RuntimeError("No target-specific PDS OK in visible UI; verify external state separately")


def cleanup(state):
    errors = []
    for serial in (SOURCE, GATEWAY):
        try:
            disable(serial)
            adb(serial, "shell", "am", "force-stop", PACKAGE)
        except Exception as error:
            errors.append(str(error))
    if state["baseline"][GATEWAY]["wifi_on"] == "0":
        adb(GATEWAY, "shell", "svc", "wifi", "disable")
    state["finalRadio"] = {s: radios(s) for s in (SOURCE, GATEWAY)}
    state["cleanupErrors"] = errors
    state["cleanupAt"] = now()
    save(state)
    assert not errors, errors
    assert state["finalRadio"] == state["baseline"]
    print(json.dumps({"cleanup": True, "restored": state["finalRadio"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "publish", "upload", "cleanup"))
    args = parser.parse_args()
    if args.phase == "prepare":
        prepare()
    else:
        data = json.loads(STATE.read_text(encoding="utf-8"))
        {"publish": publish, "upload": upload, "cleanup": cleanup}[args.phase](data)
