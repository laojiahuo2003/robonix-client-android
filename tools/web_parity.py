#!/usr/bin/env python3
"""Compare the Android vitals mapper golden against the WEB mapper on the same
real deployment fixtures.

The web client (src/robonix_client/vitals_transport.py) is the parity
reference: this script runs the web mapper over the fixtures captured from the
robot (dog_soma.yaml / dog.urdf / dog_snapshot.txt) and diffs the projection
the Android golden test freezes (dog.golden.json).

Usage:
    <robonix-client/.venv>/Scripts/python.exe tools/web_parity.py

Prints a unified diff of any mismatch and exits non-zero. Regenerate the
Android golden only after this reports PARITY OK:
    UPDATE_GOLDEN=1 ./gradlew :app:testDebugUnitTest --tests '*RobotVitalsMapperGoldenTest'
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

ANDROID_ROOT = Path(__file__).resolve().parent.parent
WEB_REPO = Path(__file__).resolve().parent.parent.parent / "robonix-client"
RES = ANDROID_ROOT / "app" / "src" / "test" / "resources"

sys.path.insert(0, str(WEB_REPO / "src"))

from google.protobuf import text_format  # noqa: E402
from robonix_client.proto import vitals_client_pb2  # noqa: E402
from robonix_client.vitals_transport import (  # noqa: E402
    normalize_robot_description,
    vitals_snapshot_to_dict,
)

ASSET_URL = "https://robonix.local/assets/"


def round2(value) -> float:
    return round(float(value), 2)


def description_canonical(desc: dict) -> dict:
    return {
        "id": desc["id"],
        "displayName": desc["displayName"],
        "family": desc["family"],
        "dimensions": {k: round2(v) for k, v in desc["dimensions"].items()},
        "components": [component_canonical(c) for c in desc["components"]],
        "renderMode": desc["render"]["mode"],
        "summary": desc["summary"],
    }


def component_canonical(c: dict) -> dict:
    return {
        "id": c["id"],
        "localId": c["localId"],
        "parentId": c["parentId"],
        "label": c["label"],
        "type": c["type"],
        "model": c.get("model", ""),
        "urdfLink": c["urdfLink"],
        "urdfJoint": c["urdfJoint"],
        "providers": c["providers"],
    }


def snapshot_canonical(hw: dict) -> dict:
    out = {}
    if hw["power"] is not None:
        p = hw["power"]
        out["power"] = {
            "socPercent": round2(p["socPercent"]),
            "voltage": round2(p["voltage"]),
            "charging": bool(p["charging"]),
            "remainingSeconds": int(p["remainingSeconds"]),
        }
    out["signals"] = [
        {
            "key": s["key"],
            "health": s["health"],
            "status": int(s["status"]),
            "observedValue": round2(s["observedValue"]),
            "referenceValue": round2(s["referenceValue"]),
            "visualState": s["visualState"],
        }
        for s in hw["signals"]
    ]
    out["bodies"] = [
        {
            "key": b["key"],
            "model": b["model"],
            "health": b["health"],
            "status": int(b["status"]),
            "message": b["message"],
            "components": [
                {
                    "id": c["id"],
                    "parentId": c["parentId"],
                    "name": c["name"],
                    "kind": c["kind"],
                    "model": c["model"],
                    "temperature": round2(c["temperature"]),
                    "errorCode": int(c["errorCode"]),
                    "enabled": bool(c["enabled"]),
                }
                for c in b["components"]
            ],
        }
        for b in hw["bodies"]
    ]
    out["componentHealth"] = [
        {
            "componentId": r["componentId"],
            "health": r["health"],
            "visualState": r["visualState"],
            "signalCount": int(r["signalCount"]),
            "detail": r["detail"],
        }
        for r in hw["componentHealth"]
    ]
    return out


def load_fixture(name: str) -> str:
    return (RES / name).read_text(encoding="utf-8")


def compare(path: str, expected, actual, diffs: list[str]) -> None:
    if isinstance(expected, dict) and isinstance(actual, dict):
        for key in sorted(set(expected) | set(actual)):
            if key not in expected:
                diffs.append(f"{path}.{key}: only in Android golden")
            elif key not in actual:
                diffs.append(f"{path}.{key}: only in web")
            else:
                compare(f"{path}.{key}", expected[key], actual[key], diffs)
    elif isinstance(expected, list) and isinstance(actual, list):
        if len(expected) != len(actual):
            diffs.append(f"{path}: length {len(expected)} (android) != {len(actual)} (web)")
            return
        for i, (e, a) in enumerate(zip(expected, actual)):
            compare(f"{path}[{i}]", e, a, diffs)
    elif isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        if abs(float(expected) - float(actual)) > 1e-6:
            diffs.append(f"{path}: {expected!r} (android) != {actual!r} (web)")
    else:
        if expected != actual:
            diffs.append(f"{path}: {expected!r} (android) != {actual!r} (web)")


def main() -> int:
    yaml_text = load_fixture("dog_soma.yaml")
    urdf_xml = load_fixture("dog.urdf")
    description = normalize_robot_description(yaml_text, urdf_xml, "simplebot", ASSET_URL)

    snapshot = vitals_client_pb2.VitalsSnapshot()
    text_format.Parse(load_fixture("dog_snapshot.txt"), snapshot)
    hw = vitals_snapshot_to_dict(snapshot, description)

    web_doc = {
        "description": description_canonical(description),
        "snapshot": snapshot_canonical(hw),
    }
    android_doc = json.loads((RES / "dog.golden.json").read_text(encoding="utf-8"))

    diffs: list[str] = []
    compare("", android_doc, web_doc, diffs)
    if diffs:
        print("PARITY MISMATCH ({n}):".format(n=len(diffs)))
        for d in diffs:
            print("  -", d)
        return 1
    print("PARITY OK: Android mapper golden == web mapper output on the real "
          "dog fixtures ({n} rows / {s} signals / {b} bodies).".format(
              n=len(web_doc["snapshot"]["componentHealth"]),
              s=len(web_doc["snapshot"]["signals"]),
              b=len(web_doc["snapshot"]["bodies"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
