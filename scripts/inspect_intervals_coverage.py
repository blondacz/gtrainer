#!/usr/bin/env python3
"""Read-only source check. Raw records/credentials stay in memory, never output."""

import argparse
import base64
from datetime import date, datetime, timedelta, timezone
import json
from pathlib import Path
import stat
import subprocess
import sys
from urllib.parse import urlencode


FIELDS = {
    "activities": (
        "id", "type", "start_date_local", "start_date", "timezone",
        "moving_time", "elapsed_time", "icu_training_load", "calories",
        "distance", "average_heartrate", "source", "sources", "source_id",
        "updated", "updated_at", "fitnessAge", "enduranceScore", "trainingStatus",
    ),
    "wellness": (
        "id", "weight", "bodyFat", "hrv", "hrvSDNN", "restingHR",
        "sleepSecs", "sleepScore", "steps", "vo2max", "atl", "ctl",
        "source", "sources", "weightSource", "bodyFatSource", "hrvSource",
        "updated", "updated_at", "fitnessAge", "enduranceScore", "trainingStatus",
    ),
}
SOURCE_FIELDS = ("source", "sources", "weightSource", "bodyFatSource", "hrvSource")
KNOWN_ORIGINS = {"GARMIN", "GARMIN_CONNECT", "STRAVA", "INTERVALS", "INTERVALS_ICU"}
MAX_BYTES = 32 * 1024 * 1024


class ApiReadError(Exception):
    def __init__(self, status):
        self.status = status


def load_key(path):
    path = Path(path).expanduser()
    if not path.is_file() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("Key file must exist and deny group/other access.")
    key = path.read_text().strip()
    if not key or any(ch.isspace() for ch in key) or ":" in key:
        raise ValueError("Key file must contain only the API key.")
    return key


def fetch_records(key, category, oldest, newest):
    query = urlencode({"oldest": oldest.isoformat(), "newest": newest.isoformat()})
    url = f"https://intervals.icu/api/v1/athlete/0/{category}?{query}"
    auth = base64.b64encode(f"API_KEY:{key}".encode()).decode()
    # Pass auth through stdin, not process arguments or a temporary config file.
    # No --location: redirects must not receive the credential.
    config = (f'url = "{url}"\nheader = "Authorization: Basic {auth}"\n'
              'header = "Accept: application/json"\n')
    result = subprocess.run(
        ["curl", "--config", "-", "--silent", "--show-error", "--request", "GET",
         "--connect-timeout", "10", "--max-time", "45", "--max-filesize", str(MAX_BYTES),
         "--write-out", "\n%{http_code}"],
        input=config, text=True, capture_output=True, timeout=50,
    )
    if result.returncode:
        raise ValueError("Transport failed; diagnostics withheld.")
    payload, separator, status = result.stdout.rpartition("\n")
    if not separator or status != "200":
        raise ApiReadError(status if status.isdigit() and len(status) == 3 else "unknown")
    if len(payload.encode()) > MAX_BYTES:
        raise ValueError("Response exceeds the inspection limit.")
    records = json.loads(payload)
    if not isinstance(records, list) or any(not isinstance(item, dict) for item in records):
        raise ValueError("Expected a list of records.")
    return records


def known_origins(value):
    if isinstance(value, str):
        normalized = value.upper().replace("-", "_").replace(" ", "_")
        return {normalized} & KNOWN_ORIGINS
    if isinstance(value, list):
        return set().union(*(known_origins(item) for item in value)) if value else set()
    if isinstance(value, dict):
        return set().union(*(known_origins(item) for item in value.values())) if value else set()
    return set()


def summarize(records, category, today):
    fields = [{"field": field,
               "present": sum(field in record for record in records),
               "non_null": sum(record.get(field) is not None for record in records)}
              for field in FIELDS[category]]
    origins = set()
    for record in records:
        for field in SOURCE_FIELDS:
            origins.update(known_origins(record.get(field)))
    observed_dates = []
    for record in records:
        raw = record.get("id") if category == "wellness" else record.get("start_date_local")
        try:
            observed_dates.append(date.fromisoformat(str(raw)[:10]))
        except ValueError:
            pass
    if not observed_dates:
        freshness = "unknown: no parseable observed dates"
    else:
        age = (today - max(observed_dates)).days
        freshness = ("future-dated records require validation" if age < 0 else
                     "latest observed date within 1 day" if age <= 1 else
                     "latest observed date within 7 days" if age <= 7 else
                     "latest observed date more than 7 days old")
    return {"category": category, "record_count": len(records), "fields": fields,
            "known_origin_labels": sorted(origins),
            "distinct_observed_days": len(set(observed_dates)),
            "freshness_band": freshness,
            "upstream_sync_status": "unverified",
            "scale_device_origin": "unverified"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-file", required=True)
    parser.add_argument("--days", type=int, default=90)
    args = parser.parse_args()
    if not 1 <= args.days <= 366:
        print("Inspection period must be between 1 and 366 days.", file=sys.stderr)
        return 1
    try:
        key = load_key(args.key_file)
        today = datetime.now(timezone.utc).date()
        oldest = today - timedelta(days=args.days)
        summaries = []
        for category in FIELDS:
            records = fetch_records(key, category, oldest, today)
            summaries.append(summarize(records, category, today))
        # No raw records, IDs, exact activity dates, metric values, or free text.
        print(json.dumps({"checked_at_utc": datetime.now(timezone.utc).isoformat(),
                          "requested_days_back": args.days,
                          "results": summaries}, indent=2))
        return 0
    except ApiReadError as error:
        print(f"Intervals.icu read failed (HTTP {error.status}); no response body displayed.", file=sys.stderr)
    except (subprocess.TimeoutExpired, TimeoutError, OSError, ValueError):
        print("Coverage inspection failed; no secret, response body, or exception details displayed.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
