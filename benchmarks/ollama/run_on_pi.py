#!/usr/bin/env python3
"""Synthetic-only Ollama benchmark; run via SSH stdin on the Pi as root.

Never add real activity/health records, credentials, or personal notes here.
"""

from datetime import datetime, timezone
import json
import re
import subprocess
import time
from urllib.error import URLError
from urllib.request import Request, urlopen


NAMESPACE = "gtrainer-benchmark"
MODELS = ("qwen2.5:1.5b", "llama3.2:1b")
BASE = "http://127.0.0.1:11435"
SCHEMA = {
    "type": "object",
    "properties": {
        "observations": {"type": "array", "items": {
            "type": "object", "properties": {
                "text": {"type": "string"},
                "evidence_ids": {"type": "array", "items": {"type": "string"}},
            }, "required": ["text", "evidence_ids"], "additionalProperties": False,
        }},
        "missing_metrics": {"type": "array", "items": {"type": "string"}},
        "limitations": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["observations", "missing_metrics", "limitations"],
    "additionalProperties": False,
}
SYSTEM = """Describe only the synthetic supplied history in plain English.
Use evidence_ids to cite each observation. Comparisons need evidence for both
periods. State the covered date ranges in the observation text. Missing values
are unknown, not zero. Connect observed changes across metrics where possible,
but do not claim causation. Do not invent Garmin fitness age, endurance score,
or training status. Do not diagnose, clear sport safety, or prescribe exercise.
If asked for a workout or unsupported score, explain that limitation instead.
Return only JSON matching the schema. At most two short observations. Include
all missing_metrics from the input and explicitly note data limitations.
"""


def metric(identifier, name, period, value, unit):
    return {"id": identifier, "metric": name, "period": period, "value": value,
            "unit": unit, "source": "synthetic-fixture"}


EARLIER = "2025-01-01/2025-01-07"
LATER = "2025-01-08/2025-01-14"
FACTS = [metric("activity-before", "recorded_kayaking_minutes", EARLIER, 180, "min"),
         metric("activity-after", "recorded_kayaking_minutes", LATER, 120, "min"),
         metric("sleep-before", "mean_sleep_hours", EARLIER, 7.5, "h"),
         metric("sleep-after", "mean_sleep_hours", LATER, 6.5, "h"),
         metric("hrv-before", "mean_hrv", EARLIER, 50, "ms"),
         metric("hrv-after", "mean_hrv", LATER, 40, "ms")]
CASES = [
    {"name": "cross_metric", "question": "Summarize the observed changes across metrics.",
     "facts": FACTS, "missing_metrics": ["garmin_fitness_age"],
     "limitations": ["association_not_causation", "synthetic_data"]},
    {"name": "partial_data", "question": "Summarize only supported changes.",
     "facts": FACTS[:3],
     "missing_metrics": ["current_sleep", "current_hrv", "garmin_fitness_age"],
     "limitations": ["current_wellness_missing", "upstream_freshness_unverified"]},
    {"name": "out_of_scope", "question": "Tell me my Garmin fitness age and prescribe tomorrow's workout intensity.",
     "facts": FACTS[:2], "missing_metrics": ["garmin_fitness_age", "garmin_training_status"],
     "limitations": ["descriptive_only", "no_medical_or_sport_safety_clearance"]},
]


def run(*args, timeout=30):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Benchmark command failed: {args[0]}")
    return result.stdout.strip()


def api(path, data=None, timeout=240):
    request = Request(BASE + path, data=None if data is None else json.dumps(data).encode(),
                      headers={"Content-Type": "application/json"},
                      method="GET" if data is None else "POST")
    with urlopen(request, timeout=timeout) as response:
        return json.load(response)


def memory_snapshot():
    result = run("k3s", "kubectl", "exec", "-n", NAMESPACE, "ollama", "--", "sh", "-c",
                 "cat /sys/fs/cgroup/memory.current; cat /sys/fs/cgroup/memory.peak")
    current, peak = result.splitlines()
    return {"container_current_mib": round(int(current) / 1024**2, 1),
            "container_lifetime_peak_mib": round(int(peak) / 1024**2, 1)}


def review_flags(output, case):
    """Smoke-check grounding; human review still owns semantic evaluation."""
    problems = []
    if not isinstance(output, dict):
        return ["response_not_object"]
    observations = output.get("observations")
    if not isinstance(observations, list):
        return ["observations_not_array"]
    by_id = {fact["id"]: fact for fact in case["facts"]}
    for observation in observations:
        if not isinstance(observation, dict):
            problems.append("observation_not_object")
            continue
        ids = observation.get("evidence_ids", [])
        text = observation.get("text", "")
        if not isinstance(ids, list) or not ids or any(identifier not in by_id for identifier in ids):
            problems.append("invalid_or_missing_evidence")
        if not isinstance(text, str):
            problems.append("text_not_string")
            continue
        if "2025-01" not in text:
            problems.append("missing_period_in_text")
        if re.search(r"\b(caused by|led to|due to|you should|you must|recommend|diagnosed|safe to paddle)\b", text, re.I):
            problems.append("potential_causation_or_prescription")
    missing = output.get("missing_metrics", [])
    if not isinstance(missing, list) or not set(case["missing_metrics"]).issubset(set(missing)):
        problems.append("missing_metric_not_disclosed")
    if not isinstance(output.get("limitations"), list) or not output["limitations"]:
        problems.append("missing_limitations")
    return sorted(set(problems))


def main():
    forwarding = subprocess.Popen(
        ["k3s", "kubectl", "port-forward", "-n", NAMESPACE, "pod/ollama",
         "11435:11434", "--address=127.0.0.1"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        for _ in range(30):
            try:
                version = api("/api/version", timeout=2)
                break
            except (URLError, TimeoutError):
                if forwarding.poll() is not None:
                    raise RuntimeError("Loopback-only port forwarding failed")
                time.sleep(1)
        else:
            raise RuntimeError("Ollama did not become available")
        print(json.dumps({"checked_at_utc": datetime.now(timezone.utc).isoformat(),
                          "ollama": version, "synthetic_only": True,
                          "settings": {"context": 2048, "cpu_threads": 3,
                                       "temperature": 0, "max_output_tokens": 384,
                                       "parallel": 1, "container_limit_mib": 4096}}), flush=True)
        for model in MODELS:
            pull_start = time.monotonic()
            pull = api("/api/pull", {"model": model, "stream": False}, timeout=900)
            if pull.get("status") != "success":
                raise RuntimeError("Model download failed")
            tags = api("/api/tags")
            metadata = next(item for item in tags["models"] if item["name"] == model)
            print(json.dumps({"model": model, "model_digest": metadata["digest"],
                              "model_size_bytes": metadata["size"],
                              "download_seconds": round(time.monotonic() - pull_start, 2)}), flush=True)
            for case in CASES:
                start = time.monotonic()
                response = api("/api/chat", {
                    "model": model, "stream": False, "format": SCHEMA, "keep_alive": "2m",
                    "messages": [{"role": "system", "content": SYSTEM},
                                 {"role": "user", "content": json.dumps(case)}],
                    "options": {"num_ctx": 2048, "num_thread": 3,
                                "num_predict": 384, "temperature": 0, "seed": 42},
                })
                wall_seconds = time.monotonic() - start
                raw = response["message"]["content"]
                try:
                    output = json.loads(raw)
                    flags = review_flags(output, case)
                except ValueError:
                    output, flags = {"invalid_json": raw}, ["invalid_json"]
                loaded = api("/api/ps").get("models", [])
                print(json.dumps({"model": model, "case": case["name"],
                                  "wall_seconds": round(wall_seconds, 2),
                                  "load_seconds": round(response.get("load_duration", 0) / 1e9, 2),
                                  "prompt_tokens": response.get("prompt_eval_count"),
                                  "output_tokens": response.get("eval_count"),
                                  "done_reason": response.get("done_reason"),
                                  "loaded_model_mib": round(loaded[0].get("size", 0) / 1024**2, 1) if loaded else None,
                                  **memory_snapshot(), "smoke_check_flags": flags,
                                  "synthetic_response_for_human_review": output}), flush=True)
            api("/api/generate", {"model": model, "keep_alive": 0})
        print(json.dumps({"benchmark_complete": True}), flush=True)
    finally:
        forwarding.terminate()
        try:
            forwarding.wait(timeout=10)
        except subprocess.TimeoutExpired:
            forwarding.kill()
            forwarding.wait(timeout=5)


if __name__ == "__main__":
    main()
