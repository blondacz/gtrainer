"""Closed one-case acquisition profile. Importing this module performs no operations."""
import copy
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
for directory in ("intake-extraction-v4", "intake-extraction-v5", "intake-extraction-v5-1"):
    location = str(HERE.parent / directory)
    if location not in sys.path:
        sys.path.insert(0, location)
import suite_v5 as parent
import oracle_v5_1 as comparison

e = parent.e
PROFILE = "guarded-live-intake-pilot-v1"
IDS = ["u02"]
LIMITS = {"roots": 1, "batches": 1, "scoredLaunches": 1, "probes": 1,
          "campaignSeconds": 3600, "cleanupReserveSeconds": 240, "retryPermitted": False,
          "restoreOriginalService": False, "appSessions": 1, "appSessionSeconds": 1200}
COUNTER = {"librarySource": "current_original_ollama_image",
    "modelPath": "/var/lib/rancher/k3s/storage/pvc-1c50c36a-05e0-4e33-8277-c4e3fbf30706_gtrainer-models_models/blobs/blobs/sha256-a3de86cd1c132c822487ededd47a324c50491393e6565cd14bafa40d0b8e686f"}


def need(value):
    if not value:
        raise ValueError("Live intake pilot refused")


def verify():
    protocol, inputs, contract, witnesses = parent.verify()
    comparison.verify()
    selected = copy.deepcopy(inputs)
    selected["cases"] = [row for row in inputs["cases"] if row["caseId"] == "u02"]
    rules = copy.deepcopy(contract)
    rules["cases"] = [row for row in contract["cases"] if row["caseId"] == "u02"]
    need(e.decode((HERE / "inputs-pilot.json").read_bytes()) == selected)
    need(e.decode((HERE / "oracle-pilot.json").read_bytes()) == rules)
    manifest = e.decode((HERE / "manifest-pilot.json").read_bytes())
    need(manifest == {"version": PROFILE, "parentV5ManifestSha256": e.digest((parent.HERE / "manifest-v5.json").read_bytes()),
        "comparisonManifestSha256": e.digest((comparison.HERE / "manifest.json").read_bytes()),
        "files": {name: e.digest((HERE / name).read_bytes()) for name in ("inputs-pilot.json", "oracle-pilot.json")}})
    return dict(protocol, profile=PROFILE, limits=LIMITS), selected, rules, {"u02": witnesses["u02"]}


def evaluate(case_id, stages, contract=None):
    need(case_id == "u02")
    return comparison.evaluate(case_id, stages, contract or verify()[2])


def freeze():
    """Explicit initial materialization only; never overwrite frozen source data."""
    _, inputs, contract, _ = parent.verify()
    selected = copy.deepcopy(inputs)
    selected["cases"] = [row for row in inputs["cases"] if row["caseId"] == "u02"]
    rules = copy.deepcopy(contract)
    rules["cases"] = [row for row in contract["cases"] if row["caseId"] == "u02"]
    import os
    for name, value in (("inputs-pilot.json", selected), ("oracle-pilot.json", rules)):
        with os.fdopen(os.open(HERE / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
            stream.write(e.compact(value))
    manifest = {"version": PROFILE, "parentV5ManifestSha256": e.digest((parent.HERE / "manifest-v5.json").read_bytes()),
        "comparisonManifestSha256": e.digest((comparison.HERE / "manifest.json").read_bytes()),
        "files": {name: e.digest((HERE / name).read_bytes()) for name in ("inputs-pilot.json", "oracle-pilot.json")}}
    with os.fdopen(os.open(HERE / "manifest-pilot.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
        stream.write(e.compact(manifest))
    verify()
