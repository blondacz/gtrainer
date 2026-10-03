#!/usr/bin/env python3
"""Owned synthetic prepared-focus test: direct Ollama, NOT app AI acceptance.

The pinned published app computes factual comparisons from seeded synthetic data.
Experimental grouping/selection never changes its prompt, schema or validator.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import app_fixtures as fixtures
import prepared_review as contract
from prepared_review_suites import SUITES, suite_data, rubric_hash
import run_app_on_pi as infra

CONTROL_POLL_SECONDS = 10
CONTROL_TIMEOUT_SECONDS = 10
CONTROL_MAX_AGE_SECONDS = 25


def read_control_state():
    pod = json.loads(infra.kubectl('-n', infra.NAMESPACE, 'get', 'pod', infra.POD, '-o', 'json',
                                  timeout=CONTROL_TIMEOUT_SECONDS))
    return {c['name']: {'ready': c['ready'], 'restarts': c['restartCount'], 'state': c['state']}
            for c in pod['status'].get('containerStatuses', [])}


def healthy_state(state):
    return set(state) == {'app', 'ollama', 'proxy'} and all(
        c['ready'] and not c['restarts'] and 'running' in c['state'] for c in state.values())


class ControlMonitor:
    """Bounded background polling; unknown/stale state fails closed, never retries."""
    def __init__(self):
        self.stop = threading.Event()
        self.failed = threading.Event()
        self.lock = threading.Lock()
        self.latest_checked = None
        self.latest_state = None
        self.thread = threading.Thread(target=self.collect, daemon=True)

    def sample_once(self):
        start = time.monotonic()
        try:
            state = read_control_state()
        except Exception as error:
            self.failed.set()
            infra.emit({'phase': 'prepared_control_failure',
                        'reason': 'control_timeout' if isinstance(error, subprocess.TimeoutExpired) else 'control_query_failed',
                        'operation': 'temporary_pod_state', 'exception_type': type(error).__name__,
                        'timeout_seconds': CONTROL_TIMEOUT_SECONDS,
                        'seconds': round(time.monotonic() - start, 3)})
            return
        with self.lock:
            self.latest_state = state
            self.latest_checked = time.monotonic()
        healthy = healthy_state(state)
        if not healthy:
            self.failed.set()
        infra.emit({'phase': 'prepared_control_sample', 'healthy': healthy,
                    'seconds': round(time.monotonic() - start, 3),
                    'containers': {name: {'ready': c['ready'], 'restarts': c['restarts'],
                                          'running': 'running' in c['state']} for name, c in state.items()}})

    def collect(self):
        while not self.stop.wait(CONTROL_POLL_SECONDS):
            self.sample_once()
            if self.failed.is_set():
                break

    def healthy(self):
        with self.lock:
            fresh = self.latest_checked is not None and time.monotonic() - self.latest_checked <= CONTROL_MAX_AGE_SECONDS
            state = self.latest_state
        if not fresh:
            if not self.failed.is_set():
                infra.emit({'phase': 'prepared_control_failure', 'reason': 'control_state_stale',
                            'operation': 'temporary_pod_state', 'maximum_age_seconds': CONTROL_MAX_AGE_SECONDS})
            self.failed.set()
        return not self.failed.is_set() and fresh and healthy_state(state or {})

    def finish(self):
        self.stop.set()
        if self.thread.ident is not None:
            self.thread.join(timeout=CONTROL_TIMEOUT_SECONDS + 5)


def receive_and_export(request_bytes, case, attempt, holder):
    """Export HTTP completion in its own worker before any control query can fail."""
    start = time.monotonic()
    try:
        status, raw = chat_http(request_bytes)
        holder.update(status=status, raw=raw)
    except Exception as error:
        holder['error_type'] = type(error).__name__
    infra.emit({'phase': 'prepared_attempt_received', 'case': case, 'attempt': attempt,
                'seconds': round(time.monotonic() - start, 3),
                'status': holder.get('status'), 'error_type': holder.get('error_type'),
                'response_base64': base64.b64encode(holder.get('raw', b'')).decode(),
                'response_sha256': infra.digest(holder.get('raw', b''))})
    holder['receipt_exported'] = True


def staging_diagnostic(status, raw):
    """Export only fixed classes/statuses, never registry URLs or signed tokens."""
    result = {'http_status': status, 'response_bytes': len(raw),
              'response_sha256': infra.digest(raw), 'error_class': 'unknown_staging_error'}
    try:
        body = json.loads(raw)
        error = body.get('error', '') if isinstance(body, dict) else ''
    except (ValueError, TypeError):
        error = ''
        result['error_class'] = 'invalid_staging_json'
    if not isinstance(error, str):
        error = ''
    lowered = error.lower()
    classes = (
        ('disk_full', ('no space left', 'disk quota exceeded')),
        ('digest_mismatch', ('digest mismatch', 'checksum mismatch')),
        ('model_not_found', ('file does not exist', 'manifest unknown', 'model not found')),
        ('unsupported_model', ('unsupported architecture', 'unsupported model', 'requires a newer version')),
        ('unexpected_eof', ('unexpected eof', 'unexpected end of file')),
        ('network_timeout', ('timeout', 'deadline exceeded')),
        ('dns_failure', ('no such host', 'temporary failure in name resolution')),
        ('tls_failure', ('x509:', 'tls handshake', 'certificate verify')),
        ('connection_failure', ('connection refused', 'connection reset', 'network is unreachable')),
        ('permission_denied', ('permission denied',)),
    )
    for name, markers in classes:
        if any(marker in lowered for marker in markers):
            result['error_class'] = name
            break
    upstream = re.search(r'(?:status code|http status|http error)\s*[:=]?\s*([45][0-9]{2})\b', lowered)
    if upstream:
        result['upstream_http_status'] = int(upstream.group(1))
        if result['error_class'] == 'unknown_staging_error':
            result['error_class'] = 'upstream_http_error'
    if not error and result['error_class'] == 'unknown_staging_error':
        result['error_class'] = 'unexpected_staging_response'
    return result


def stage_model(model):
    """One staging call per authorized run; diagnostics cannot retry it."""
    if model not in contract.MODELS:
        raise ValueError('Only an explicitly supported synthetic candidate may be staged')
    try:
        status, _, raw = infra.request(infra.OLLAMA, '/api/pull', 'POST', {'model': model, 'stream': False}, timeout=900)
    except Exception as error:
        infra.emit({'phase': 'prepared_staging_diagnostic', 'model': model, 'http_status': None,
                    'error_class': 'transport_failure', 'exception_type': type(error).__name__})
        raise RuntimeError('Staging transport failed; no retry') from None
    diagnostic = staging_diagnostic(status, raw)
    try:
        body = json.loads(raw)
    except (ValueError, TypeError):
        body = None
    success = status == 200 and isinstance(body, dict) and body.get('status') == 'success' and 'error' not in body
    if success:
        diagnostic['error_class'] = None
    infra.emit({'phase': 'prepared_staging_diagnostic', 'model': model, 'success': success, **diagnostic})
    if not success:
        raise RuntimeError('Staging failed; bounded diagnostics retained; no retry')


def chat_http(request_bytes):
    """Send exactly the recorded bytes and retain original bounded HTTP bytes."""
    query = Request(infra.OLLAMA + '/api/chat', data=request_bytes, method='POST',
                    headers={'Content-Type': 'application/json'})
    try:
        response = urlopen(query, timeout=contract.CALL_SECONDS)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read(131073)
        if len(raw) > 131072:
            raise RuntimeError('Bounded response reader exceeded')
        return response.status, raw


def model_health():
    state = infra.temporary_state()
    return bool(state) and all(c['ready'] and not c['restarts'] and 'running' in c['state'] for c in state.values())


def response_flags(response, prepared, binding, expected):
    raw = response.get('message', {}).get('content')
    if not isinstance(raw, str):
        return ['invalid_response_shape'], ['not_validated']
    flags = contract.validate(raw, prepared, binding)
    if response.get('done') is not True or response.get('done_reason') != 'stop':
        flags.append('incomplete_generation')
    if response.get('message', {}).get('thinking'):
        flags.append('unexpected_thinking')
    return flags, contract.relevance_flags(raw, prepared, expected) if not flags else ['not_validated']


def package(owner, model, suite='known-v1'):
    if len(owner) != 32 or any(c not in '0123456789abcdef' for c in owner) or model not in contract.MODELS:
        raise ValueError('Explicit unique owner and supported model required')
    suite_data(suite)
    if suite == 'fresh-v1' and model != 'qwen3.5:4b':
        raise ValueError('Fresh review testing is approved only for Qwen3.5 4B')
    directory = Path(__file__).parent
    names = ('app_fixtures', 'app_unseen_fixtures', 'prepared_review', 'prepared_review_fresh_fixtures',
             'prepared_review_suites', 'run_app_on_pi', 'run_prepared_review_on_pi')
    sources = {name: (directory / (name + '.py')).read_text() for name in names}
    sources['app_capture_proxy'] = (directory / 'app_capture_proxy.py').read_text()
    hashes = {name: hashlib.sha256(value.encode()).hexdigest() for name, value in sources.items()}
    script = 'import json,sys,types\nsources=' + repr(sources) + '\n'
    script += 'print(json.dumps({"phase":"operator_sources","synthetic_only":True,"source_sha256":' + repr(hashes) + '}),flush=True)\n'
    script += 'for name in ' + repr(names) + ':\n'
    script += ' module=types.ModuleType(name); module.__file__=name+".py"; sys.modules[name]=module\n'
    script += ' exec(compile(sources[name],module.__file__,"exec"),module.__dict__)\n'
    script += 'sys.argv=["run_prepared_review_on_pi.py","--owner",' + repr(owner) + ',"--model",' + repr(model) + ',"--suite",' + repr(suite) + ',"--approve-synthetic-run"]\n'
    script += 'sys.modules["run_prepared_review_on_pi"].main(sources["app_capture_proxy"])\n'
    return script


def main(proxy_source=None):
    if os.environ.get('GITHUB_ACTIONS'):
        raise RuntimeError('CI must not contact the home cluster')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--model', choices=contract.MODELS, required=True)
    parser.add_argument('--approve-synthetic-run', required=True, action='store_true')
    parser.add_argument('--package', action='store_true')
    parser.add_argument('--suite', choices=SUITES, default='known-v1')
    args = parser.parse_args()
    if args.package:
        print(package(args.owner, args.model, args.suite), end='')
        return
    if len(args.owner) != 32 or any(c not in '0123456789abcdef' for c in args.owner):
        raise ValueError('Unique owner required')
    assert os.geteuid() == 0 and Path('/proc/meminfo').exists()
    if args.suite == 'fresh-v1' and args.model != 'qwen3.5:4b':
        raise ValueError('Fresh review testing is approved only for Qwen3.5 4B')
    profile = fixtures.model_profile(args.model)
    assert profile['digest'] == contract.MODELS[args.model]
    run_cases, requests, rotations = suite_data(args.suite)
    baseline = infra.live_state()
    assert baseline and all(c['ready'] for c in baseline)
    assert infra.host_state()['host_available_mib'] >= 5600
    assert __import__('shutil').disk_usage('/var/lib/rancher/k3s').free >= 7 * 1024**3
    assert not infra.kubectl('get', 'namespace', infra.NAMESPACE, '--ignore-not-found', '-o', 'json')
    descriptor = os.open(infra.PROGRESS, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    namespace_uid = None
    forwards = []
    monitor = None
    control = None
    worker = None
    cookie = csrf = None
    completed = 0
    try:
        if proxy_source is None:
            proxy_source = Path(__file__).with_name('app_capture_proxy.py').read_text()
        with tempfile.TemporaryDirectory(prefix='gtrainer-prepared-review-', dir='/run') as directory:
            path = Path(directory) / 'synthetic.sqlite3'
            fixtures.database(path, run_cases)
            manifest = infra.manifest(args.owner, path.read_bytes(), proxy_source, args.model)
            infra.kubectl('create', '-f', '-', data=json.dumps(manifest['items'][0]).encode())
            namespace_uid = json.loads(infra.kubectl('get', 'namespace', infra.NAMESPACE, '-o', 'json'))['metadata']['uid']
            manifest['items'] = manifest['items'][1:]
            infra.kubectl('create', '-f', '-', data=json.dumps(manifest).encode(), timeout=120)
        infra.owned(args.owner, namespace_uid)
        infra.kubectl('-n', infra.NAMESPACE, 'wait', '--for=condition=Ready', 'pod/' + infra.POD, '--timeout=300s', timeout=310)
        for namespace, target, port in ((infra.NAMESPACE, 'pod/' + infra.POD, '11480:8080'),
                                         (infra.NAMESPACE, 'pod/' + infra.POD, '11481:11438'),
                                         ('gtrainer', 'service/gtrainer', '11482:8080')):
            forwards.append(subprocess.Popen(['k3s', 'kubectl', 'port-forward', '-n', namespace, target, port, '--address=127.0.0.1'],
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        for _ in range(30):
            try:
                assert infra.request(infra.APP, '/healthz', timeout=2)[0] == 200
                assert infra.model_api('/api/version', timeout=2)['version'] == '0.35.0'
                with urlopen(infra.LIVE_HEALTH, timeout=2) as response:
                    assert response.status == 200
                break
            except (OSError, URLError):
                assert all(p.poll() is None for p in forwards)
                time.sleep(1)
        else:
            raise RuntimeError('Loopback forwarding unavailable')
        monitor = infra.Monitor(); monitor.thread.start()
        assert infra.model_api('/api/tags')['models'] == []
        infra.emit({'phase': 'prepared_preflight', 'synthetic_only': True, 'profile': contract.PROFILE,
                    'checked_at_utc': datetime.now(timezone.utc).isoformat(), 'owner': args.owner, 'model': profile,
                    'baseline_live_state': baseline, 'app_image': fixtures.APP_IMAGE,
                    'system_sha256': hashlib.sha256(contract.SYSTEM.encode()).hexdigest(),
                    'fixture_sha256': contract.digest(run_cases), 'settings': contract.OPTIONS,
                    'call_seconds': contract.CALL_SECONDS, 'job_seconds': contract.JOB_SECONDS,
                    'maximum_attempts_per_case': 2, 'not_production_app_acceptance': True,
                    'control_poll_seconds': CONTROL_POLL_SECONDS, 'control_timeout_seconds': CONTROL_TIMEOUT_SECONDS,
                    'control_maximum_age_seconds': CONTROL_MAX_AGE_SECONDS,
                    'response_export_independent_of_control': True,
                    'fixture_suite': args.suite, 'rubric_sha256': rubric_hash(requests),
                    'fresh_structural_variants': args.suite == 'fresh-v1',
                    'external_blinded_holdout': False,
                    'known_structural_fixtures_not_holdout': args.suite == 'known-v1', **infra.host_state()})
        stage_model(args.model)
        models = infra.model_api('/api/tags')['models']
        assert len(models) == 1 and models[0]['name'] == args.model and models[0]['digest'] == profile['digest']
        assert not any(k in models[0] for k in ('remote_host', 'remote_model'))
        infra.emit({'phase': 'prepared_staged', 'model_metadata': models[0]})
        labels = {'app.kubernetes.io/managed-by': infra.MANAGED, 'gtrainer.io/benchmark-owner': args.owner, 'gtrainer.io/synthetic-only': 'true'}
        deny = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': 'no-runtime-egress', 'namespace': infra.NAMESPACE, 'labels': labels},
                'spec': {'podSelector': {}, 'policyTypes': ['Egress'], 'egress': []}}
        infra.kubectl('create', '-f', '-', data=json.dumps(deny).encode())
        blocked = json.loads(infra.exec_proxy("import socket,json\ntry:\n socket.create_connection(('1.1.1.1',443),timeout=3).close()\nexcept OSError:\n print(json.dumps({'blocked':True}))\nelse:\n raise SystemExit('Runtime egress allowed')"))
        assert blocked['blocked'] is True
        assert infra.request(infra.APP, '/api/analysis-input')[0] == 401
        status, headers, login = infra.request(infra.APP, '/api/login', 'POST', {'password': fixtures.PASSWORD})
        assert status == 200
        cookie, csrf = headers['Set-Cookie'].split(';')[0], json.loads(login)['csrfToken']

        def private(path, method='GET', body=None):
            status, headers, raw = infra.request(infra.APP, path, method, body, cookie, csrf)
            assert status == 200 and headers['Cache-Control'] == 'no-store'
            return raw, json.loads(raw)

        def snapshot(case):
            query = f"?oldest={case['oldest']}&newest={case['newest']}"
            raw, report = private('/api/trends' + query)
            _, summary = private('/api/analysis-input' + query)
            assert summary['evidenceReportSha256'] == infra.digest(raw)
            infra.verify_fixture_comparisons(case, summary)
            assert all(point['source'] == 'synthetic-fixture' for period in ('current', 'previous')
                       for metric in report[period]['wellness'] + [m for sport in report[period]['sports'] for m in sport['metrics']]
                       for point in metric['points'])
            return infra.digest(raw), report, summary, infra.prepared_packet(summary), raw

        history_before = infra.digest(private('/api/history?oldest=2020-01-01&newest=2020-12-31')[0])
        assert private('/api/models')[1]['selectedModelId'] is None and infra.captures() == []
        infra.emit({'phase': 'prepared_runtime_ready', 'runtime_egress_blocked': True, 'app_ai_off': True, 'history_sha256_before': history_before})
        control = ControlMonitor()
        control.sample_once()
        assert control.healthy()
        control.thread.start()
        for index, case in enumerate(run_cases):
            if monitor.failed.is_set() or infra.live_state() != baseline or not model_health():
                raise RuntimeError('Resource/container guard failed')
            report_hash, report, summary, packet, report_bytes = snapshot(case)
            if case['name'] not in requests:
                assert not contract.sufficient(packet, summary)
                infra.emit({'phase': 'prepared_sparse_gate', 'case': case['name'], 'model_calls': 0,
                            'report_sha256': report_hash, 'analysis_input': summary, 'source_packet': packet})
                completed += 1
                continue
            assert contract.sufficient(packet, summary)
            focus, expected_kinds = requests[case['name']]
            prepared = contract.prepare(packet, focus, rotation=rotations[index])
            binding = contract.digest(prepared)
            facts = contract.factual_rendering(prepared)
            infra.emit({'phase': 'prepared_case', 'case': case['name'], 'packet': prepared, 'packet_sha256': binding,
                        'source_packet': packet, 'report': report, 'report_sha256': report_hash,
                        'report_base64': base64.b64encode(report_bytes).decode(),
                        'analysis_input': summary, 'factual_rendering': facts, 'expected_kinds_private_oracle': sorted(expected_kinds)})
            start = time.monotonic()
            feedback = None
            for attempt in (1, 2):
                payload = contract.request(args.model, prepared, feedback)
                request_bytes = contract.compact(payload)
                assert all(marker not in request_bytes for marker in (fixtures.PASSWORD.encode(), b'sourceRecordId', b'points', b'expected_kinds'))
                infra.emit({'phase': 'prepared_attempt_started', 'case': case['name'], 'attempt': attempt,
                            'request_base64': base64.b64encode(request_bytes).decode(), 'request_sha256': infra.digest(request_bytes)})
                holder = {}
                worker = threading.Thread(target=receive_and_export,
                                          args=(request_bytes, case['name'], attempt, holder), daemon=True)
                worker.start()
                worker.join(timeout=5)
                probe_start = time.monotonic()
                assert snapshot(case)[0] == report_hash
                infra.emit({'phase': 'prepared_chart_probe', 'case': case['name'], 'attempt': attempt,
                            'seconds': round(time.monotonic() - probe_start, 4), 'generation_pending': worker.is_alive()})
                while worker.is_alive():
                    if monitor.failed.wait(1) or not control.healthy() or time.monotonic() - start >= contract.JOB_SECONDS:
                        infra.emit({'phase': 'prepared_stop_state', 'case': case['name'], 'attempt': attempt,
                                    'last_observed_temporary_state': control.latest_state,
                                    'control_guard_failed': control.failed.is_set(), 'resource_guard_failed': monitor.failed.is_set(),
                                    'job_seconds_elapsed': round(time.monotonic() - start, 3)})
                        infra.owned(args.owner, namespace_uid)
                        infra.kubectl('-n', infra.NAMESPACE, 'delete', 'pod', infra.POD, '--wait=true', '--timeout=45s', timeout=55)
                        raise RuntimeError('Resource/job guard stopped owned pod; no retry')
                assert holder.get('receipt_exported') is True
                if holder.get('status') != 200 or not control.healthy() or not model_health() or monitor.failed.is_set() or infra.live_state() != baseline:
                    raise RuntimeError('Transport/resource failure; no corrective retry')
                response = json.loads(holder['raw'])
                flags, relevance = response_flags(response, prepared, binding, expected_kinds)
                raw_claims = response.get('message', {}).get('content', '')
                accepted = not flags and not relevance
                fresh_hash = snapshot(case)[0]
                if fresh_hash != report_hash:
                    flags = sorted(set(flags + ['evidence_changed']))
                    relevance = ['not_validated']
                    accepted = False
                rendered = contract.focus_rendering(raw_claims, prepared, binding) if accepted else None
                infra.emit({'phase': 'prepared_attempt_result', 'case': case['name'], 'attempt': attempt,
                            'validity_flags': flags, 'relevance_flags': relevance, 'accepted': accepted,
                            'raw_model_content': raw_claims, 'rendering': rendered,
                            'prompt_tokens': response.get('prompt_eval_count'), 'output_tokens': response.get('eval_count'),
                            'done_reason': response.get('done_reason'), 'load_duration': response.get('load_duration'),
                            'report_sha256_after_generation': fresh_hash})
                for _ in range(15):
                    if not infra.model_api('/api/ps')['models']:
                        break
                    if monitor.failed.wait(1):
                        raise RuntimeError('Guard failed during unload')
                else:
                    raise RuntimeError('Model did not unload')
                infra.emit({'phase': 'prepared_unloaded', 'case': case['name'], 'attempt': attempt, 'verified': True})
                feedback = flags or relevance
                if accepted or not contract.can_retry(feedback, attempt, time.monotonic() - start):
                    break
            assert not monitor.failed.is_set() and infra.live_state() == baseline and model_health()
            assert control.healthy()
            infra.emit({'phase': 'prepared_case_outcome', 'case': case['name'], 'accepted': accepted,
                        'final_attempt': attempt, 'job_seconds': round(time.monotonic() - start, 3)})
            completed += 1
        assert infra.digest(private('/api/history?oldest=2020-01-01&newest=2020-12-31')[0]) == history_before
        assert infra.captures() == []  # No app AI request or proxy rewrite ever occurred.
        private('/api/logout', 'POST')
        assert infra.request(infra.APP, '/api/models')[0] == 401
        infra.emit({'phase': 'prepared_controls', 'history_unchanged': True, 'history_sha256_after': history_before,
                    'no_app_ai_calls': True, 'logout_verified': True})
        logs_safe = True
        for container in ('app', 'ollama', 'proxy'):
            logs = infra.kubectl('-n', infra.NAMESPACE, 'logs', infra.POD, '-c', container, decode=False)
            if any(marker in logs for marker in (fixtures.PASSWORD.encode(), csrf.encode(), b'"preparedState"', b'"sourceRecordId"', b'synthetic-wellness-', b'"requested_focus"')):
                logs_safe = False
        assert logs_safe
        infra.emit({'phase': 'prepared_log_scan', 'passed': True})
        telemetry = monitor.finish(); monitor = None
        infra.emit({'phase': 'prepared_telemetry', **telemetry})
        assert infra.live_state() == baseline and not telemetry['resource_guard_failed'] and model_health()
        control.finish()
        assert control.healthy()
        infra.emit({'phase': 'prepared_complete', 'completed_cases': completed, 'final_live_state': infra.live_state()})
    except Exception as error:
        infra.emit({'phase': 'prepared_failed', 'error_type': type(error).__name__, 'completed_cases': completed,
                    'code_locations': [{'function': t.name, 'line': t.lineno} for t in __import__('traceback').extract_tb(error.__traceback__)],
                    'diagnostics_withheld': True, **infra.host_state()})
        if namespace_uid:
            try:
                infra.emit({'phase': 'prepared_failure_state', 'temporary_state': infra.temporary_state()})
            except Exception:
                pass
        raise RuntimeError('Prepared synthetic test stopped; no retry/fallback') from None
    finally:
        if control:
            control.finish()
        if monitor:
            infra.emit({'phase': 'prepared_telemetry_after_failure', **monitor.finish()})
        for forwarding in forwards:
            forwarding.terminate()
            try:
                forwarding.wait(timeout=10)
            except subprocess.TimeoutExpired:
                forwarding.kill(); forwarding.wait(timeout=5)
        if namespace_uid:
            infra.owned(args.owner, namespace_uid)
            infra.kubectl('delete', 'namespace', infra.NAMESPACE, '--wait=true', '--timeout=120s', timeout=130)
            if worker:
                worker.join(timeout=15)
                if worker.is_alive():
                    infra.emit({'phase': 'prepared_capture_shutdown_failed', 'pending_response_unknown': True})
                    raise RuntimeError('Owned resources removed but capture worker did not stop; progress retained')
            infra.emit({'phase': 'prepared_cleanup', 'temporary_namespace_removed': True,
                        'final_live_state': infra.live_state(), **infra.host_state()})
        infra.PROGRESS.unlink()


if __name__ == '__main__':
    main()
