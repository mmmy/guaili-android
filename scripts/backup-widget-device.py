"""Read-only device backup before installing/running widget tests. Never restores automatically."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import tarfile


def backup(serial: str) -> Path:
    adb = ["adb", "-s", serial]
    subprocess.run(adb + ["get-state"], check=True, capture_output=True, timeout=15)
    root = Path(__file__).resolve().parents[1] / "build" / "device-backups"
    directory = root / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    directory.mkdir(parents=True)
    packages = subprocess.run(adb + ["shell", "pm", "list", "packages", "com.gouge.guaili"], check=True, capture_output=True, timeout=20).stdout.decode()
    installed = "package:com.gouge.guaili" in packages.splitlines()
    if installed:
        archive = directory / "datastore.tar"
        with archive.open("wb") as output:
            subprocess.run(adb + ["exec-out", "run-as", "com.gouge.guaili", "tar", "-cf", "-", "files/datastore"], stdout=output, stderr=subprocess.PIPE, check=True, timeout=30)
        with tarfile.open(archive) as saved:
            names = saved.getnames()
            if "files/datastore" not in names:
                raise RuntimeError("Datastore backup is missing; do not proceed with installation.")
            for member in saved.getmembers():
                if member.isfile() and len(saved.extractfile(member).read()) != member.size:
                    raise RuntimeError("Backup is truncated; do not proceed with installation.")
        for label, args in {
            "widgets": ["dumpsys", "appwidget"],
            "package": ["dumpsys", "package", "com.gouge.guaili"],
        }.items():
            result = subprocess.run(adb + ["shell", *args], check=True, capture_output=True, timeout=30)
            (directory / f"{label}.txt").write_bytes(result.stdout)
    (directory / "manifest.json").write_text(json.dumps({"serial": serial, "packageInstalled": installed, "restored": False}, indent=2), encoding="utf-8")
    return directory


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    print(backup(parser.parse_args().serial))
