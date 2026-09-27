#!/usr/bin/env python3
"""Exercise real phone/watch persistence on two disposable, unpaired emulators.

This replaces only Data Layer transport with an ADB adapter. Install matching
standaloneDebug APKs and finish Mona's first-run setup before running it.
It adds a test schedule and intake; it never runs against a physical device.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

PACKAGE = "com.deliacheminot.mona.dev"


class Device:
    def __init__(self, adb, serial):
        self.command = [adb, "-s", serial]
        if self.run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError(f"{serial} must be a disposable emulator")
        self.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files")

    def run(self, *args):
        return subprocess.check_output(self.command + list(args), stderr=subprocess.STDOUT).decode()

    def exchange(self, request, action, receiver, cold=False):
        with tempfile.TemporaryDirectory(prefix="mona-wear-") as directory:
            source = Path(directory) / "input.json"
            source.write_text(json.dumps(request))
            self.run("push", str(source), "/data/local/tmp/mona-wear-input.json")
            self.run("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/mona-wear-input.json", "files/wear-debug-input.json")
            self.run("shell", "rm", "/data/local/tmp/mona-wear-input.json")
        if cold:
            self.run("shell", "am", "force-stop", PACKAGE)
        self.run("shell", "run-as", PACKAGE, "rm", "-f", "files/wear-debug-output.json")
        self.run("shell", "am", "broadcast", "-n", f"{PACKAGE}/{receiver}", "-a", action)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                result = json.loads(self.run("exec-out", "run-as", PACKAGE, "cat", "files/wear-debug-output.json"))
                if result.get("status") != "complete":
                    raise RuntimeError(f"Debug adapter failed: {result}")
                return result
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                time.sleep(0.25)
        raise TimeoutError("No result from the debug adapter; inspect adb logcat")

    def phone(self, commands=()):
        entries = [{"path": f"/mona/v1/commands/{c['installationId']}/{c['id']}", "json": json.dumps(c)} for c in commands]
        return self.exchange(entries, "com.deliacheminot.mona.WEAR_DEBUG_SYNC", "com.deliacheminot.mona.wear.WearDebugReceiver", cold=True)

    def watch(self, **request):
        request["requestId"] = str(uuid.uuid4())
        result = self.exchange(request, "com.deliacheminot.mona.WEAR_DEBUG_STATE", "com.deliacheminot.mona.wear.WearDebugReceiver")
        assert result["requestId"] == request["requestId"]
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True, help="Phone emulator adb serial")
    parser.add_argument("--watch", required=True, help="Wear emulator adb serial")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb"))
    parser.add_argument("--output", type=Path, default=Path("build/wear-verification"))
    parser.add_argument("--seed-only", action="store_true", help="Copy current Mona state to the watch for manual UI testing")
    parser.add_argument("--relay-only", action="store_true", help="Deliver pending watch UI actions and return phone receipts")
    args = parser.parse_args()
    if args.phone == args.watch:
        parser.error("Use two different emulators")
    args.output.mkdir(parents=True, exist_ok=True)
    phone, watch = Device(args.adb, args.phone), Device(args.adb, args.watch)

    def save(name, value):
        (args.output / f"{name}.json").write_text(json.dumps(value, indent=2))
        return value

    if args.relay_only:
        state = watch.watch()
        pending = [a["command"] for a in state["actions"] if a["status"] == "pending"]
        result = save("relayed", phone.phone(pending))
        save("watch-relayed", watch.watch(snapshot=result["snapshot"], results=result["results"]))
        print(f"Relayed {len(pending)} actions. Inspect {args.output} for receipts.")
        return

    base = save("phone-before", phone.phone())
    watch.watch(snapshot=base["snapshot"])
    if args.seed_only:
        print(f"Watch seeded with Mona snapshot revision {base['snapshot']['revision']}.")
        return

    medication = {
        "name": f"Wear verification {time.time_ns()}", "dose": "2", "unitDose": None,
        "moleculeName": "estradiol", "unit": "mg", "route": "oral", "ester": None,
        "startDate": time.strftime("%Y-%m-%d"),
        "recurrence": {"type": "daily", "times": [540], "notify": True}, "doseOverrides": [],
    }
    created = watch.watch(createMedication=medication)
    create_id = created["actionId"]
    watch.run("shell", "am", "force-stop", PACKAGE)
    restored = watch.watch()
    command = next(a["command"] for a in restored["actions"] if a["command"]["id"] == create_id)
    assert next(a for a in restored["actions"] if a["command"]["id"] == create_id)["status"] == "pending"
    result = save("created", phone.phone([command]))
    receipt = next(r for r in result["results"] if r["id"] == create_id)
    assert receipt["status"] == "applied", receipt
    schedule = next(s for s in result["snapshot"]["schedules"] if s["id"] == receipt["entityId"])
    assert schedule["name"] == medication["name"]
    assert len(result["snapshot"]["schedules"]) == len(base["snapshot"]["schedules"]) + 1
    watch.watch(snapshot=result["snapshot"], results=result["results"])

    queued = watch.watch(recordDose={"scheduleId": schedule["id"], "dose": "2", "at": int(time.time() * 1000), "scheduledMinute": 540})
    dose_id = queued["actionId"]
    watch.run("shell", "am", "force-stop", PACKAGE)
    restored = save("watch-offline-restart", watch.watch())
    dose = next(a["command"] for a in restored["actions"] if a["command"]["id"] == dose_id)
    result = save("logged", phone.phone([dose]))
    receipt = next(r for r in result["results"] if r["id"] == dose_id)
    assert receipt["status"] == "applied", receipt
    matching = [i for i in result["snapshot"]["history"] if i["scheduleId"] == schedule["id"]]
    assert len(matching) == 1 and matching[0]["zoneId"] == dose["payload"]["zoneId"]
    intake_id = matching[0]["id"]

    receipt_first = watch.watch(results=result["results"])
    assert next(a for a in receipt_first["actions"] if a["command"]["id"] == dose_id)["awaitingSnapshot"]
    confirmed = watch.watch(snapshot=result["snapshot"])
    assert not next(a for a in confirmed["actions"] if a["command"]["id"] == dose_id)["awaitingSnapshot"]
    retry = save("retried", phone.phone([dose]))
    matching = [i for i in retry["snapshot"]["history"] if i["scheduleId"] == schedule["id"]]
    assert len(matching) == 1 and matching[0]["id"] == intake_id
    save("watch-confirmed", watch.watch(snapshot=retry["snapshot"], results=retry["results"]))
    print(f"PASS: medication {schedule['id']}, intake {intake_id}; offline restart, cold phone commit, receipt ordering, retry deduplication.")
    print(f"Artifacts: {args.output}. Data Layer radio transport still requires a paired-device test.")


if __name__ == "__main__":
    main()
