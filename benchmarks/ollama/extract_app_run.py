#!/usr/bin/env python3
"""Generate a compact synthetic evidence artifact, retaining actual raw claim bytes."""
import argparse
import base64
import hashlib
import json
from pathlib import Path

from summarize_app_run import collected_cases, summarize


def extract(raw):
    assert len(raw) <= 8 * 1024**2
    records = [json.loads(line) for line in raw.splitlines()]
    output = summarize(records)
    output['source_jsonl_sha256'] = hashlib.sha256(raw).hexdigest()
    output['retention'] = 'Compact extraction; original synthetic JSONL retains full reports, packets, transport and samples. Original raw claims below are never repaired. Received-but-unfinalized outcomes do not imply runner completion or app acceptance.'
    for case, original in zip(output['cases'], collected_cases(records)):
        replay = case['replay']
        rendered = replay.pop('rendered')
        replay['validated_observation_count'] = len(rendered) if rendered is not None else 0
        case['http_report_sha256'] = original['analysis_input']['evidenceReportSha256']
        for event in ('request', 'forwarded_request', 'response'):
            entries = [e for e in original['capture'] if e['event'] == event]
            if not entries:
                continue
            assert len(entries) == 1
            entry = entries[0]
            body = base64.b64decode(entry['body_base64'], validate=True)
            assert hashlib.sha256(body).hexdigest() == entry['body_sha256']
            case[event + '_body_sha256'] = entry['body_sha256']
            if event == 'request':
                case['request_started_utc'] = entry.get('started_utc')
            if event == 'response' and entry.get('status', 200) == 200:
                payload = json.loads(body)
                content = payload.get('message', {}).get('content')
                if isinstance(content, str):
                    case['raw_model_content'] = content
                    case['original_claim_sha256'] = hashlib.sha256(content.encode()).hexdigest()
                case['response_created_at'] = payload.get('created_at')
        case['report_evaluated_on_utc'] = original['analysis_input']['evaluatedOnUtc']
    output['controls_exported'] = any(r['phase'] == 'functional_controls' for r in records)
    output['log_marker_scan_passed'] = next((r['app_model_logs_marker_scan_passed'] for r in records if r['phase'] == 'log_marker_scan'), None)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('synthetic_jsonl', type=Path)
    args = parser.parse_args()
    print(json.dumps(extract(args.synthetic_jsonl.read_bytes()), indent=2, ensure_ascii=False, allow_nan=False))


if __name__ == '__main__':
    main()
