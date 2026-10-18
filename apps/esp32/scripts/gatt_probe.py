"""Bounded PC -> ESP32 GATT discovery/subscription probe; no posts or resets.

Run from apps/esp32:
uv run --no-project --with bleak==1.1.1 python scripts/gatt_probe.py
It temporarily uses the ESP32's one BLE connection, then disconnects.
"""
import argparse
import asyncio
from datetime import datetime
import json
from pathlib import Path
import time

from bleak import BleakClient, BleakScanner

SERVICE = "b5e89a01-f9da-45c2-9111-2de7a7441d24"
RX = "483c3867-c832-4c60-9a96-7018059eaf74"
TX = "49d60590-c23f-4a0a-a58e-f2f2f606f94d"


async def probe(args, result):
    started = time.monotonic()

    def stage(name, **values):
        event = dict(stage=name, elapsed=round(time.monotonic() - started, 3), **values)
        result["stages"].append(event)
        print(json.dumps(event), flush=True)

    stage("scan")
    device = await BleakScanner.find_device_by_address(args.address, timeout=8)
    if device is None:
        raise RuntimeError("ESP32 not advertising during 8-second scan; may be occupied")
    stage("found", address=device.address, device_name=device.name)
    async with BleakClient(device, timeout=15, winrt={"use_cached_services": False}) as client:
        stage("discovered", connected=client.is_connected, mtu=client.mtu_size)
        service = client.services.get_service(SERVICE)
        if service is None:
            raise RuntimeError("Mesh service missing")
        characteristics = {c.uuid: c for c in service.characteristics}
        if RX not in characteristics or TX not in characteristics:
            raise RuntimeError("Mesh RX/TX characteristic missing")
        stage("mesh-service", characteristics=[dict(uuid=c.uuid, properties=c.properties,
              descriptors=[dict(uuid=d.uuid, handle=d.handle) for d in c.descriptors])
              for c in service.characteristics])
        if args.subscribe:
            received = asyncio.Event()
            notifications = []

            def notify(_, data):
                notifications.append(bytes(data))
                received.set()

            await client.start_notify(TX, notify)
            stage("subscribed")
            await asyncio.wait_for(received.wait(), timeout=5)
            await asyncio.sleep(0.2)
            stage("notification", chunks=len(notifications), bytes=sum(map(len, notifications)))
            await client.stop_notify(TX)
    stage("disconnected")
    result["ok"] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", default="5C:01:3B:13:93:32")
    parser.add_argument("--subscribe", action="store_true")
    parser.add_argument("--output", type=Path, default=Path(".pio/pc-gatt-probe.json"))
    args = parser.parse_args()
    result = dict(at=datetime.now().astimezone().isoformat(), address=args.address, ok=False, stages=[])
    try:
        asyncio.run(asyncio.wait_for(probe(args, result), timeout=35))
    except Exception as error:
        result["error"] = dict(type=type(error).__name__, message=str(error))
        print(json.dumps(result["error"]), flush=True)
    finally:
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    raise SystemExit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
