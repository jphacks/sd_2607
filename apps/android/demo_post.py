"""At most one explicit demo test message; no cleanup that stops the working demo."""
import datetime
import json
import re
import time
from pathlib import Path
from clean_pair import adb, click, seek, texts, card, network

SOURCE = "4f4ebc6f"
GATEWAY = "T4AIOC732121JRB"
OUT = Path(__file__).with_name("hardware-working-demo-message.json")
assert not OUT.exists(), "Do not generate a second demo message"
content = "TEST ONLY - NOT DISASTER INFORMATION DEMO-" + datetime.datetime.now().strftime("%H%M%S")
state = {"at": datetime.datetime.now().astimezone().isoformat(), "content": content, "source": "OPPO Peer", "gateway": "ASUS Demo"}

def save():
    OUT.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

assert not network(SOURCE)["transports"], "Source is not offline"
assert network(GATEWAY)["physicalValidated"], "Gateway is not online"
seek(SOURCE, lambda t: t.startswith("公開する本文"))
root = texts(SOURCE)
edits = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
field = next(n for n in edits if any(c.get("text", "").startswith("公開する本文") for c in n.iter("node")))
assert not field.get("text"), "Preserve existing user draft; do not post automatically"
x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.get("bounds")))
adb(SOURCE, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
adb(SOURCE, "shell", "input", "text", content.replace(" ", "%s"))
adb(SOURCE, "shell", "input", "keyevent", "4")
assert any(n.get("text") == content for n in texts(SOURCE).iter("node") if n.get("class") == "android.widget.EditText"), "Draft mismatch; do not publish"
state["publishRequested"] = True
save()
click(SOURCE, "署名して保存・配送")
seek(SOURCE, lambda t: t == content)
adb(SOURCE, "shell", "input", "swipe", "540", "1900", "540", "1500", "300")
values = card(SOURCE, content)
assert values, "Post was requested: never retry creation"
state["sourceUi"] = values
prefix = next(re.match(r"([0-9a-f]{12}) /", t)[1] for t in values if re.match(r"[0-9a-f]{12} /", t))
state["prefix"] = prefix
pid = adb(GATEWAY, "shell", "pidof", "org.mndt.demo").strip()
for _ in range(25):
    raw = adb(GATEWAY, "logcat", "-d", "--pid=" + pid, "-v", "time", "-s", "Exchange:I", "*:S")
    hits = [l for l in raw.splitlines() if re.search(r": accepted " + prefix + r"[0-9a-f]{52}", l)]
    if hits:
        state["gatewayReceipt"] = hits
        state["eventId"] = re.search(r": accepted ([0-9a-f]{64})", hits[0])[1]
        save()
        print(json.dumps(state, ensure_ascii=False), flush=True)
        break
    time.sleep(2)
else:
    state["receiptPending"] = True
    save()
    print(json.dumps(state, ensure_ascii=False), flush=True)
