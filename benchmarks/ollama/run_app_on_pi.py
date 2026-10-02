#!/usr/bin/env python3
"""Operator-only, isolated synthetic published-app → selected local-model test.

Creates and removes exclusively owned temporary resources, never deploy/gtrainer.
No source credential, live login, production record, PVC, or hosted inference.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from app_fixtures import (APP_IMAGE, APP_REVISION, SYSTEM_PROMPT_SHA256, MODEL, MODEL_PROFILES, NAMESPACE,
                          OLLAMA_IMAGE, ORIGIN, PASSWORD, PYTHON_IMAGE, cases, catalogue, database, model_profile, verifier)

POD = 'synthetic'
APP = 'http://127.0.0.1:11480'
OLLAMA = 'http://127.0.0.1:11481'
LIVE_HEALTH = 'http://127.0.0.1:11482/healthz'
PROGRESS = Path('/run/gtrainer-app-benchmark-progress.jsonl')
MAX_BODY = 1048576
MANAGED = 'gtrainer-synthetic-benchmark'
EMIT_LOCK = threading.Lock()


def digest(body):
    return hashlib.sha256(body).hexdigest()


def emit(value):
    line = json.dumps(value, allow_nan=False)
    with EMIT_LOCK:
        print(line, flush=True)
        with PROGRESS.open('a') as progress:
            progress.write(line + '\n')


def run(*args, timeout=30, data=None, decode=True):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Synthetic benchmark infrastructure command failed; diagnostics withheld')
    return result.stdout.decode().strip() if decode else result.stdout


def kubectl(*args, **kwargs):
    return run('k3s', 'kubectl', *args, **kwargs)


def request(base, path, method='GET', body=None, cookie=None, csrf=None, timeout=150):
    headers = {'Origin': ORIGIN}
    if cookie:
        headers['Cookie'] = cookie
    if csrf:
        headers['X-CSRF-Token'] = csrf
    if body is not None:
        headers['Content-Type'] = 'application/json'
    query = Request(base + path, method=method, headers=headers,
                    data=None if body is None else json.dumps(body, allow_nan=False).encode())
    try:
        response = urlopen(query, timeout=timeout)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read(MAX_BODY + 1)
        if len(payload) > MAX_BODY:
            raise RuntimeError('Synthetic HTTP response exceeded reader budget')
        return response.status, response.headers, payload


def model_api(path, body=None, timeout=30):
    status, _, raw = request(OLLAMA, path, 'GET' if body is None else 'POST', body, timeout=timeout)
    if status != 200:
        raise RuntimeError('Isolated model API unavailable; diagnostics withheld')
    return json.loads(raw) if raw else {}


def security():
    return {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001,
            'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True,
            'capabilities': {'drop': ['ALL']}, 'seccompProfile': {'type': 'RuntimeDefault'}}


def manifest(owner, database_bytes, proxy_source, candidate=MODEL, skill=None, control=None):
    labels = {'app.kubernetes.io/managed-by': MANAGED, 'gtrainer.io/benchmark-owner': owner,
              'gtrainer.io/synthetic-only': 'true'}
    ns = {'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': NAMESPACE, 'labels': labels}}
    config = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'synthetic-fixtures', 'namespace': NAMESPACE, 'labels': labels},
              'immutable': True, 'binaryData': {'synthetic.sqlite3': base64.b64encode(database_bytes).decode()},
              'data': {'models.json': json.dumps(catalogue(candidate)), 'password-verifier': verifier(), 'proxy.py': proxy_source}}
    ingress = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
        'metadata': {'name': 'no-inbound', 'namespace': NAMESPACE, 'labels': labels},
        'spec': {'podSelector': {}, 'policyTypes': ['Ingress'], 'ingress': []}}
    mounts = lambda names: [{'name': name, 'mountPath': path, **({'readOnly': True} if name == 'fixtures' else {})}
                            for name, path in names]
    ollama = {'name': 'ollama', 'image': OLLAMA_IMAGE, 'securityContext': security(),
        'env': [{'name': key, 'value': value} for key, value in {
            'HOME': '/models', 'OLLAMA_MODELS': '/models/blobs', 'OLLAMA_HOST': '127.0.0.1:11438',
            'OLLAMA_NO_CLOUD': '1', 'OLLAMA_NUM_PARALLEL': '1', 'OLLAMA_MAX_LOADED_MODELS': '1',
            'OLLAMA_MAX_QUEUE': '2', 'OLLAMA_CONTEXT_LENGTH': '2048'}.items()],
        'resources': {'requests': {'cpu': '500m', 'memory': '512Mi', 'ephemeral-storage': '4Gi'},
                      'limits': {'cpu': '3', 'memory': '5Gi', 'ephemeral-storage': '5Gi'}},
        'volumeMounts': mounts([('models', '/models'), ('temporary', '/tmp')]),
        'readinessProbe': {'exec': {'command': ['ollama', 'list']}, 'initialDelaySeconds': 5, 'periodSeconds': 10}}
    app = {'name': 'app', 'image': APP_IMAGE, 'securityContext': security(),
        'env': [{'name': key, 'value': value} for key, value in {
            'GTRAINER_HOST': '0.0.0.0', 'GTRAINER_PORT': '8080', 'GTRAINER_DATABASE_FILE': '/data/gtrainer.sqlite3',
            'GTRAINER_PASSWORD_VERIFIER_FILE': '/fixtures/password-verifier', 'GTRAINER_PUBLIC_ORIGIN': ORIGIN,
            'GTRAINER_SSH_TUNNEL_ONLY': 'true', 'GTRAINER_LOCAL_MODELS_FILE': '/fixtures/models.json',
            'JAVA_OPTS': '-Xmx512m -Dorg.sqlite.lib.path=/app/native'}.items()],
        'resources': {'requests': {'cpu': '100m', 'memory': '256Mi'}, 'limits': {'cpu': '500m', 'memory': '1Gi', 'ephemeral-storage': '256Mi'}},
        'volumeMounts': mounts([('data', '/data'), ('fixtures', '/fixtures'), ('temporary', '/tmp')]),
        'readinessProbe': {'httpGet': {'path': '/healthz', 'port': 8080}, 'initialDelaySeconds': 5, 'periodSeconds': 5}}
    proxy = {'name': 'proxy', 'image': PYTHON_IMAGE, 'securityContext': security(),
        'command': ['python', '-u', '/fixtures/proxy.py'],
        'env': [{'name': 'GTRAINER_SYNTHETIC_BENCHMARK', 'value': '1'}, {'name': 'PYTHONDONTWRITEBYTECODE', 'value': '1'}],
        'resources': {'requests': {'cpu': '20m', 'memory': '32Mi'}, 'limits': {'cpu': '250m', 'memory': '128Mi', 'ephemeral-storage': '64Mi'}},
        'volumeMounts': mounts([('fixtures', '/fixtures'), ('capture', '/capture'), ('data', '/data')]),
        'readinessProbe': {'exec': {'command': ['sh', '-c', 'test -f /capture/ready']},
                            'initialDelaySeconds': 5, 'periodSeconds': 10}}
    if skill is not None:
        config['data']['skill.txt'] = skill
        proxy['env'].append({'name': 'GTRAINER_SYNTHETIC_PROMPT_EXPERIMENT', 'value': '1'})
        if control is not None:
            config['data']['control-skill.txt'] = control
            proxy['env'].append({'name': 'GTRAINER_SYNTHETIC_CONTROL_SKILL', 'value': '1'})
    else:
        assert control is None
    pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': POD, 'namespace': NAMESPACE, 'labels': labels},
        'spec': {'automountServiceAccountToken': False, 'activeDeadlineSeconds': 7200, 'restartPolicy': 'Never',
            'nodeSelector': {'kubernetes.io/arch': 'arm64'}, 'securityContext': {'fsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
            'initContainers': [{'name': 'seed-only-synthetic', 'image': APP_IMAGE, 'securityContext': security(),
                'command': ['sh', '-c', 'set -eu\ncp /fixtures/synthetic.sqlite3 /data/gtrainer.sqlite3\nchmod 600 /data/gtrainer.sqlite3'],
                'resources': {'requests': {'cpu': '10m', 'memory': '16Mi'}, 'limits': {'cpu': '100m', 'memory': '128Mi'}},
                'volumeMounts': mounts([('data', '/data'), ('fixtures', '/fixtures')])}],
            'containers': [ollama, app, proxy], 'volumes': [
                {'name': 'models', 'emptyDir': {'sizeLimit': '5Gi'}}, {'name': 'data', 'emptyDir': {'sizeLimit': '128Mi'}},
                {'name': 'capture', 'emptyDir': {'sizeLimit': '32Mi'}}, {'name': 'temporary', 'emptyDir': {'sizeLimit': '64Mi'}},
                {'name': 'fixtures', 'configMap': {'name': 'synthetic-fixtures', 'defaultMode': 292}}]}}
    return {'apiVersion': 'v1', 'kind': 'List', 'items': [ns, config, ingress, pod]}


def host_state():
    mem = {line.split(':')[0]: int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines()}
    thermal = Path('/sys/class/thermal/thermal_zone0/temp')
    return {'host_available_mib': round(mem['MemAvailable'] / 1024, 1),
            'temperature_c': int(thermal.read_text()) / 1000 if thermal.exists() else None}


def live_state():
    pods = json.loads(kubectl('-n', 'gtrainer', 'get', 'pods', '-o', 'json'))
    return [{'name': p['metadata']['name'], 'uid': p['metadata']['uid'],
             'image': next(s['image'] for s in p['spec']['containers'] if s['name'] == c['name']), 'image_id': c.get('imageID'),
             'ready': c['ready'], 'restarts': c['restartCount']}
            for p in pods['items'] for c in p['status'].get('containerStatuses', [])]


def temporary_state():
    pod = json.loads(kubectl('-n', NAMESPACE, 'get', 'pod', POD, '-o', 'json'))
    return {c['name']: {'ready': c['ready'], 'restarts': c['restartCount'], 'state': c['state']}
            for c in pod['status'].get('containerStatuses', [])}


def exec_proxy(code, data=None):
    return kubectl('-n', NAMESPACE, 'exec', '-i', POD, '-c', 'proxy', '--', 'python', '-c', code, data=data)


def captures():
    raw = exec_proxy("from pathlib import Path; p=Path('/capture/chat.jsonl'); assert p.stat().st_size<=1048576; print(p.read_text(),end='')")
    return [json.loads(line) for line in raw.splitlines()]


def fault(value):
    assert value in (None, 'outage', 'unsupported-output')
    exec_proxy("from pathlib import Path; import sys; p=Path('/capture/synthetic-fault'); v=sys.stdin.read(); "
               "p.write_text(v) if v else p.unlink(missing_ok=True)", data=(value or '').encode())


def change_status(value):
    assert value in ('SUCCESS', 'KEY_REJECTED')
    exec_proxy("import sqlite3,sys; c=sqlite3.connect('/data/gtrainer.sqlite3'); "
               "c.execute('UPDATE sync_status SET read_status=?',(sys.stdin.read(),)); c.commit(); c.close()", data=value.encode())


class Monitor:
    def __init__(self):
        self.samples = []
        self.stop = threading.Event()
        self.failed = threading.Event()
        self.thread = threading.Thread(target=self.collect, daemon=True)

    def collect(self):
        while not self.stop.is_set():
            sample = host_state()
            for name, base in (('live', LIVE_HEALTH), ('synthetic', APP + '/healthz')):
                start = time.monotonic()
                try:
                    with urlopen(base, timeout=3) as response:
                        sample[name + '_health_ok'] = response.status == 200
                        response.read(1024)
                except (OSError, URLError):
                    sample[name + '_health_ok'] = False
                sample[name + '_health_seconds'] = round(time.monotonic() - start, 4)
            for container in ('ollama', 'app', 'proxy'):
                try:
                    text = kubectl('-n', NAMESPACE, 'exec', POD, '-c', container, '--', 'sh', '-c',
                                   'cat /sys/fs/cgroup/memory.current\ncat /sys/fs/cgroup/memory.peak', timeout=10)
                    current, peak = map(int, text.splitlines())
                    sample[container + '_current_mib'] = round(current / 1024**2, 1)
                    sample[container + '_lifetime_peak_mib'] = round(peak / 1024**2, 1)
                except Exception:
                    sample[container + '_memory_unavailable'] = True
            self.samples.append(sample)
            emit({'phase': 'resource_sample', 'synthetic_only': True, **sample})
            if not sample['live_health_ok'] or sample['host_available_mib'] < 768 or (sample['temperature_c'] or 0) >= 85:
                self.failed.set()  # Operator loop aborts its pending request and exclusively owned pod.
            self.stop.wait(5)

    def finish(self):
        self.stop.set(); self.thread.join(timeout=40)
        return {'samples': self.samples, 'resource_guard_failed': self.failed.is_set()}


def prepared_packet(summary):
    """Independent fixture/report oracle, not the production preparer."""
    selected = [c for c in summary['comparisons'] if c['sport'] is not None and c['key'] == 'movingTime'][:2]
    selected += [next(c for c in summary['comparisons'] if c['sport'] is None and c['key'] == key) for key in ('sleepSecs', 'hrv')]
    evidence = []
    for index, comparison in enumerate(selected):
        facts = {f['period']: f for f in summary['facts'] if f['sport'] == comparison['sport'] and f['metric'] == comparison['key']}
        change = comparison['absoluteChange']
        state = 'unavailable' if change is None else 'increased' if change > 0 else 'decreased' if change < 0 else 'unchanged'
        evidence.append({'id': f'e{index}', 'sport': comparison['sport'], 'metric': comparison['key'], 'unit': comparison['unit'],
            'previousOldest': facts['previous']['oldest'], 'previousNewest': facts['previous']['newest'],
            'currentOldest': facts['current']['oldest'], 'currentNewest': facts['current']['newest'],
            'previousValue': comparison['previousValue'], 'currentValue': comparison['currentValue'],
            'preparedState': state, 'flags': comparison['flags']})
    return {'evidence': evidence}


def verify_fixture_comparisons(case, summary):
    for sport, before, after in case['sports']:
        comparison = next(c for c in summary['comparisons'] if c['sport'] == sport and c['key'] == 'movingTime')
        count = case['activity_samples_per_period']
        assert comparison['previousValue'] == before * count and comparison['currentValue'] == after * count
        assert comparison['previousSamples'] == comparison['currentSamples'] == count
    for key, expected in (('sleepSecs', case['sleep']), ('hrv', case['hrv'])):
        comparison = next(c for c in summary['comparisons'] if c['sport'] is None and c['key'] == key)
        assert (comparison['previousValue'], comparison['currentValue']) == tuple(expected)


def verify_capture(entries, packet, model, arm=None, skill=None, control=None):
    requests = [e for e in entries if e['event'] == 'request']
    if len(requests) != 1:
        raise RuntimeError('Expected exactly one synthetic chat request')
    sent = base64.b64decode(requests[0]['body_base64'], validate=True)
    assert digest(sent) == requests[0]['body_sha256']
    payload = json.loads(sent)
    assert payload['model'] == model and payload['stream'] is False and payload['think'] is False
    assert payload['keep_alive'] == '0s'
    assert payload['options'] == {'num_ctx': 2048, 'num_thread': 3, 'num_predict': 256, 'temperature': 0, 'seed': 42}
    assert len(payload['messages']) == 2 and [m['role'] for m in payload['messages']] == ['system', 'user']
    assert payload['messages'][0]['content'].startswith('Select descriptive typed observations from prepared comparisons. Return JSON only:')
    assert digest(payload['messages'][0]['content'].encode()) == SYSTEM_PROMPT_SHA256
    assert json.loads(payload['messages'][1]['content']) == packet
    assert not any(v in sent for v in (PASSWORD.encode(), b'sourceRecordId', b'synthetic-wellness-', b'synthetic-cross_metric-', b'points'))
    ids = payload['format']['properties']['observations']['items']['properties']['evidence']['items']['properties']['id']['enum']
    assert ids == [e['id'] for e in packet['evidence']]
    forwarded = [e for e in entries if e['event'] == 'forwarded_request']
    if arm is not None:
        from app_prompt_experiment import rewrite_body
        assert len(forwarded) == 1 and forwarded[0]['id'] == requests[0]['id']
        assert forwarded[0]['prompt_arm'] == arm and forwarded[0]['explicit_prompt_experiment'] is True
        forwarded_bytes = base64.b64decode(forwarded[0]['body_base64'], validate=True)
        assert digest(forwarded_bytes) == forwarded[0]['body_sha256']
        assert forwarded_bytes == rewrite_body(sent, arm, skill, control)
    else:
        assert forwarded == []
    for entry in entries:
        if entry['event'] == 'response':
            assert entry['id'] == requests[0]['id'] and digest(base64.b64decode(entry['body_base64'], validate=True)) == entry['body_sha256']
    return payload


def owned(owner, namespace_uid):
    ns = json.loads(kubectl('get', 'namespace', NAMESPACE, '-o', 'json'))
    assert ns['metadata']['uid'] == namespace_uid
    assert ns['metadata']['labels']['gtrainer.io/benchmark-owner'] == owner
    assert ns['metadata']['labels']['app.kubernetes.io/managed-by'] == MANAGED
    resources = kubectl('-n', NAMESPACE, 'get', 'pods,configmaps,networkpolicies,services,secrets,persistentvolumeclaims,deployments,jobs',
        '-o', 'jsonpath={range .items[*]}{.kind}{"\\t"}{.metadata.name}{"\\t"}{.metadata.labels.gtrainer\\.io/benchmark-owner}{"\\n"}{end}')
    for row in resources.splitlines():
        kind, name, resource_owner = (row.split('\t') + ['', ''])[:3]
        if kind == 'ConfigMap' and name == 'kube-root-ca.crt':
            continue
        assert resource_owner == owner
        assert kind in ('Pod', 'ConfigMap', 'NetworkPolicy')
    return True


def main(proxy_source=None, skill=None, control=None):
    if os.environ.get('GITHUB_ACTIONS'):
        raise RuntimeError('CI must not contact the home cluster')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--approve-synthetic-run', action='store_true', required=True)
    parser.add_argument('--candidate', choices=MODEL_PROFILES, default=MODEL)
    parser.add_argument('--prompt-experiment', action='store_true')
    parser.add_argument('--prompt-experiment-profile', choices=('baseline-v1', 'card-v2-unseen'), default='baseline-v1')
    args = parser.parse_args()
    if args.prompt_experiment:
        from app_prompt_experiment import prompts, fixture_cases
        if skill is None:
            control, skill = prompts(args.prompt_experiment_profile)
        run_cases = fixture_cases(args.prompt_experiment_profile)
        assert (control is not None) == (args.prompt_experiment_profile == 'card-v2-unseen')
        assert args.candidate == 'qwen3:4b-instruct'  # Explicitly approved candidate; no fallback.
    else:
        assert skill is None and control is None and args.prompt_experiment_profile == 'baseline-v1'
        run_cases = cases()
    profile = model_profile(args.candidate)
    tag, alias, manifest_digest = profile['tag'], profile['alias'], profile['digest']
    assert len(args.owner) == 32 and all(c in '0123456789abcdef' for c in args.owner)
    assert os.geteuid() == 0 and Path('/proc/meminfo').exists()
    if proxy_source is None:
        proxy_source = Path(__file__).with_name('app_capture_proxy.py').read_text()
        if args.prompt_experiment:
            from app_prompt_experiment import instrument_proxy_source
            proxy_source = instrument_proxy_source(proxy_source, Path(__file__).with_name('app_prompt_experiment.py').read_text())
    baseline = live_state()
    assert baseline and all(c['ready'] for c in baseline)
    assert host_state()['host_available_mib'] >= 5600
    assert __import__('shutil').disk_usage('/var/lib/rancher/k3s').free >= 7 * 1024**3
    assert not kubectl('get', 'namespace', NAMESPACE, '--ignore-not-found', '-o', 'json')
    descriptor = os.open(PROGRESS, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    namespace_uid = None
    forwards = []
    monitor = None
    cookie = csrf = None
    results = []
    try:
        with tempfile.TemporaryDirectory(prefix='gtrainer-synthetic-app-', dir='/run') as directory:
            path = Path(directory) / 'synthetic.sqlite3'
            database(path, run_cases)
            fixtures = manifest(args.owner, path.read_bytes(), proxy_source, args.candidate, skill, control)
            kubectl('create', '-f', '-', data=json.dumps(fixtures['items'][0]).encode(), timeout=30)
            namespace_uid = json.loads(kubectl('get', 'namespace', NAMESPACE, '-o', 'json'))['metadata']['uid']
            fixtures['items'] = fixtures['items'][1:]
            kubectl('create', '-f', '-', data=json.dumps(fixtures).encode(), timeout=120)
        owned(args.owner, namespace_uid)
        kubectl('-n', NAMESPACE, 'wait', '--for=condition=Ready', 'pod/' + POD, '--timeout=300s', timeout=310)
        for namespace, target, port in ((NAMESPACE, 'pod/' + POD, '11480:8080'), (NAMESPACE, 'pod/' + POD, '11481:11438'),
                                        ('gtrainer', 'service/gtrainer', '11482:8080')):
            forwards.append(subprocess.Popen(['k3s', 'kubectl', 'port-forward', '-n', namespace, target, port, '--address=127.0.0.1'],
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        for _ in range(30):
            try:
                assert request(APP, '/healthz', timeout=2)[0] == 200
                assert model_api('/api/version', timeout=2)['version'] == '0.35.0'
                with urlopen(LIVE_HEALTH, timeout=2) as response:
                    assert response.status == 200
                break
            except (OSError, URLError):
                assert all(p.poll() is None for p in forwards)
                time.sleep(1)
        else:
            raise RuntimeError('Isolated forwarding unavailable')
        monitor = Monitor(); monitor.thread.start()
        assert model_api('/api/tags')['models'] == []
        emit({'phase': 'preflight', 'synthetic_only': True, 'owner': args.owner, 'app_image': APP_IMAGE, 'app_revision': APP_REVISION,
              'ollama_image': OLLAMA_IMAGE, 'proxy_image': PYTHON_IMAGE, 'candidate': profile,
              'baseline_live_state': baseline, **host_state()})
        start = time.monotonic()
        assert model_api('/api/pull', {'model': tag, 'stream': False}, timeout=900).get('status') == 'success'
        models = model_api('/api/tags')['models']
        assert len(models) == 1 and models[0]['name'] == tag and models[0]['digest'] == manifest_digest
        assert not any(key in models[0] for key in ('remote_host', 'remote_model'))
        model_api('/api/copy', {'source': tag, 'destination': alias})
        models = model_api('/api/tags')['models']
        assert {m['name'] for m in models} == {tag, alias} and all(m['digest'] == manifest_digest for m in models)
        emit({'phase': 'staging_complete', 'download_seconds': round(time.monotonic() - start, 2),
              'model': tag, 'manifest_digest': manifest_digest, 'model_size_bytes': models[0]['size'], 'alias_same_weights': True})
        labels = {'app.kubernetes.io/managed-by': MANAGED, 'gtrainer.io/benchmark-owner': args.owner, 'gtrainer.io/synthetic-only': 'true'}
        deny = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': 'no-runtime-egress', 'namespace': NAMESPACE, 'labels': labels},
                'spec': {'podSelector': {}, 'policyTypes': ['Egress'], 'egress': []}}
        kubectl('create', '-f', '-', data=json.dumps(deny).encode())
        # The only attempted outbound request is public, contains no health input, and must fail.
        blocked = json.loads(exec_proxy("import socket,json\ntry:\n socket.create_connection(('1.1.1.1',443),timeout=3).close()\nexcept OSError as e:\n print(json.dumps({'blocked':True,'errno':e.errno}))\nelse:\n raise SystemExit('Runtime egress unexpectedly allowed')"))
        assert blocked['blocked'] is True
        assert request(APP, '/api/models')[0] == 401
        assert request(APP, '/api/analysis', 'POST', {})[0] == 401
        status, headers, login = request(APP, '/api/login', 'POST', {'password': PASSWORD})
        assert status == 200
        cookie, csrf = headers['Set-Cookie'].split(';')[0], json.loads(login)['csrfToken']

        def private(path, method='GET', body=None):
            status, headers, raw = request(APP, path, method, body, cookie, csrf)
            assert status == 200 and headers['Cache-Control'] == 'no-store'
            return headers, raw, json.loads(raw)

        def select(identifier):
            return private('/api/models', 'PUT', {'modelId': identifier})[2]

        def snapshot(case):
            query = f"?oldest={case['oldest']}&newest={case['newest']}"
            headers, raw, report = private('/api/trends' + query)
            sha = digest(raw)
            assert headers['X-Evidence-Report-Sha256'] == sha
            summary = private('/api/analysis-input' + query)[2]
            assert summary['evidenceReportSha256'] == sha
            assert all(point['source'] == 'synthetic-fixture' for period in ('current', 'previous')
                       for metric in report[period]['wellness'] + [m for group in report[period]['sports'] for m in group['metrics']]
                       for point in metric['points'])
            return report, summary, sha

        history_before = digest(private('/api/history?oldest=2020-01-01&newest=2020-12-31')[1])
        default = private('/api/models')[2]
        assert default['selectedModelId'] is None and default['hostedEnabled'] is False
        assert len(default['models']) == 2 and captures() == []
        selected = select(profile['id'])
        assert captures() == []  # Selection alone does not call the model.
        emit({'phase': 'runtime_ready', 'runtime_egress_probe': blocked, 'default_off': True,
               'selection_without_inference': True, 'history_sha256_before': history_before})
        if args.prompt_experiment:
            emit({'phase': 'prompt_experiment', 'synthetic_only': True,
                   'baseline_prompt_sha256': SYSTEM_PROMPT_SHA256, 'skill_prompt_sha256': digest(skill.encode()),
                  'control_prompt_sha256': SYSTEM_PROMPT_SHA256 if control is None else digest(control.encode()),
                  'profile': args.prompt_experiment_profile,
                  'arm_labels': {'A': 'published-app prompt' if control is None else 'instruction card v1',
                                 'B': 'instruction card v1' if control is None else 'instruction card v2'},
                  'fixture_cases_sha256': digest(json.dumps(run_cases, separators=(',', ':'), allow_nan=False).encode()),
                   'experimental_transport_system_message_only': True, 'production_app_unchanged': True,
                   'paired_order': 'AB,BA,AB,BA,AB,BA', 'held_out_generalization_test': False,
                  'fresh_structural_variants': args.prompt_experiment_profile == 'card-v2-unseen'})

        sequence = [(run_cases[0], 'first_cold')] + [(run_cases[0], 'repeat_with_unload')] + [(c, 'new_prompt_with_unload') for c in run_cases[1:]]
        # Exercise the selected alias using the first fixture. It is explicitly the same weights.
        sequence.append((run_cases[0], 'selected_alias_same_weights'))
        if args.prompt_experiment:
            from app_prompt_experiment import sequence as prompt_sequence
            sequence = prompt_sequence(run_cases)
        for index, (case, repetition) in enumerate(sequence, 1):
            if monitor.failed.is_set() or live_state() != baseline:
                raise RuntimeError('Live dashboard/host resource guard failed')
            if repetition == 'selected_alias_same_weights':
                selected = select(profile['aliasId'])
            arm = repetition[-1] if args.prompt_experiment else None
            if arm is not None:
                exec_proxy("from pathlib import Path; import sys; v=sys.stdin.read(); assert v in ('A','B'); "
                           "Path('/capture/synthetic-prompt-arm').write_text(v)", data=arm.encode())
            report, summary, sha = snapshot(case)
            verify_fixture_comparisons(case, summary)
            packet = prepared_packet(summary)
            count = len(captures())
            body = {'oldest': case['oldest'], 'newest': case['newest'], 'evidenceReportSha256': sha,
                    'modelId': selected['selectedModelId'], 'selectionVersion': selected['selectionVersion']}
            assert request(APP, '/api/analysis', 'POST', body, cookie=cookie)[0] == 403
            emit({'phase': 'case_started', 'case': case['name'], 'index': index, 'total': len(sequence), 'repetition': repetition})
            start = time.monotonic()
            response_holder = {}

            def generate():
                try:
                    response_holder['result'] = private('/api/analysis', 'POST', body)[2]
                except Exception:
                    response_holder['error'] = True

            worker = threading.Thread(target=generate, daemon=True); worker.start()
            chart_probe = None
            worker.join(timeout=5)
            if worker.is_alive():
                generating = any(e['event'] == 'request' for e in captures()[count:])
                chart_start = time.monotonic()
                _, _, chart_sha = snapshot(case)
                chart_probe = {'model_request_seen': generating, 'generation_pending': True,
                               'wall_seconds': round(time.monotonic() - chart_start, 4), 'report_unchanged': chart_sha == sha}
                emit({'phase': 'chart_probe', 'synthetic_only': True, 'case': case['name'], 'repetition': repetition, **chart_probe})
            while worker.is_alive():
                if monitor.failed.wait(1):
                    owned(args.owner, namespace_uid)
                    kubectl('-n', NAMESPACE, 'delete', 'pod', POD, '--wait=true', '--timeout=45s', timeout=55)
                    raise RuntimeError('Resource guard stopped exclusively owned synthetic pod')
            if response_holder.get('error'):
                raise RuntimeError('Synthetic app request failed; diagnostics withheld')
            result = response_holder['result']
            wall = round(time.monotonic() - start, 3)
            emit({'phase': 'case_received', 'synthetic_only': True, 'case': case['name'], 'repetition': repetition,
                  'wall_seconds': wall, 'app_response': result})
            assert result['evidenceReportSha256'] == sha
            entries = captures()[count:]
            if case['name'] in ('sparse_activity', 'missing_current_wellness'):
                assert result['reason'] == 'insufficient_input' and result['observations'] == [] and entries == []
            else:
                # A timed-out app caller may close before the instrument finishes its upstream read.
                for _ in range(45):
                    if any(e['event'] == 'response' for e in entries):
                        break
                    if monitor.failed.wait(1):
                        raise RuntimeError('Resource guard failed while draining isolated inference')
                    entries = captures()[count:]
                emit({'phase': 'captured_transport', 'synthetic_only': True, 'case': case['name'], 'repetition': repetition,
                      'report': report, 'analysis_input': summary, 'prepared_packet_oracle': packet, 'capture': entries})
                verify_capture(entries, packet, tag if selected['selectedModelId'] == profile['id'] else alias, arm, skill, control)
                if result['status'] == 'unavailable':
                    assert result['observations'] == [] and result['reason'] in ('unusable_model_output', 'model_unavailable', 'model_timeout', 'evidence_changed')
                else:
                    assert result['reason'] == 'validated_typed_observations' and result['observations']
                    facts = {f['evidenceId']: f for f in summary['facts']}
                    for observation in result['observations']:
                        assert all(facts[f['evidenceId']] == f for f in observation['supportingMetrics'])
                        assert observation['evidenceIds'] == [f['evidenceId'] for f in observation['supportingMetrics']]
                for _ in range(15):
                    if not model_api('/api/ps')['models']:
                        break
                    if monitor.failed.wait(1):
                        raise RuntimeError('Resource guard failed during model unload')
                else:
                    raise RuntimeError('keep_alive=0s did not release the isolated model')
            fresh_sha = digest(private('/api/trends' + f"?oldest={case['oldest']}&newest={case['newest']}")[1])
            if result['reason'] == 'evidence_changed':
                assert fresh_sha != sha and result['observations'] == []
            else:
                assert fresh_sha == sha
                assert chart_probe is None or chart_probe['report_unchanged']
            record = {'phase': 'case_result', 'synthetic_only': True, 'case': case['name'], 'repetition': repetition,
                      'wall_seconds': wall, 'report': report, 'analysis_input': summary, 'prepared_packet_oracle': packet,
                      'app_response': result, 'capture': entries, 'chart_probe': chart_probe,
                       'temporary_state': temporary_state(), **host_state()}
            record['report_sha256_after_generation'] = fresh_sha
            if arm is not None:
                record['prompt_arm'] = arm
                record['skill_prompt_sha256'] = digest(skill.encode())
                record['prompt_experiment_profile'] = args.prompt_experiment_profile
                record['control_prompt_sha256'] = SYSTEM_PROMPT_SHA256 if control is None else digest(control.encode())
            results.append(record); emit(record)
            if monitor.failed.is_set() or live_state() != baseline or any(not c['ready'] or c['restarts'] for c in temporary_state().values()):
                raise RuntimeError('Synthetic resource/container guard failed')

        selected = select(profile['id'])
        if args.prompt_experiment:
            exec_proxy("from pathlib import Path; Path('/capture/synthetic-prompt-arm').write_text('A')")
        case = run_cases[0]
        report, summary, sha = snapshot(case)
        body = {'oldest': case['oldest'], 'newest': case['newest'], 'evidenceReportSha256': sha,
                'modelId': selected['selectedModelId'], 'selectionVersion': selected['selectionVersion']}
        controls = []
        count = len(captures())
        change_status('KEY_REJECTED')
        stale = private('/api/analysis', 'POST', body)[2]
        assert stale['reason'] == 'evidence_changed' and stale['observations'] == [] and len(captures()) == count
        report_stale = snapshot(case)[0]
        assert all(c['readStatus'] == 'KEY_REJECTED' for c in report_stale['sourceStatus'])
        change_status('SUCCESS')
        select(None)
        off = private('/api/analysis', 'POST', body)[2]
        assert off['reason'] == 'model_not_selected' and off['observations'] == [] and len(captures()) == count
        selected = select(profile['id'])
        changed = private('/api/analysis', 'POST', body)[2]
        assert changed['reason'] == 'model_selection_changed' and changed['observations'] == [] and len(captures()) == count
        controls += [{'name': 'stale_source_status', 'result': stale}, {'name': 'off', 'result': off}, {'name': 'selection_version', 'result': changed}]
        body['selectionVersion'] = selected['selectionVersion']
        for injected, reason in (('outage', 'model_unavailable'), ('unsupported-output', 'unusable_model_output')):
            fault(injected)
            result = private('/api/analysis', 'POST', body)[2]
            assert result['reason'] == reason and result['observations'] == []
            controls.append({'name': injected, 'explicit_injected_fault_not_model_quality': True, 'result': result})
            fault(None)
        assert digest(private('/api/history?oldest=2020-01-01&newest=2020-12-31')[1]) == history_before
        private('/api/logout', 'POST')
        cookie = None
        assert request(APP, '/api/models')[0] == 401
        telemetry = monitor.finish(); monitor = None
        # Preserve completed controls/telemetry before any fallible log scan.
        emit({'phase': 'functional_controls', 'controls': controls, 'history_sha256_after': history_before,
              'capture_instrument_synthetic_only': True})
        emit({'phase': 'telemetry', **telemetry})
        logs_safe = True
        for container in ('app', 'ollama', 'proxy'):
            # Ollama logs may contain non-UTF-8 terminal bytes. Scan original
            # bytes, never replacement-decode them into misleading evidence.
            logs = kubectl('-n', NAMESPACE, 'logs', POD, '-c', container, decode=False)
            # Inspect without emitting logs or credentials. Numeric health values alone are ambiguous.
            if any(marker in logs for marker in (PASSWORD.encode(), csrf.encode(), b'synthetic-wellness-', b'synthetic-cross_metric-',
                                                  b'"preparedState"', b'"sourceRecordId"', b'Garmin fitness age is 21')):
                logs_safe = False
        assert logs_safe
        emit({'phase': 'log_marker_scan', 'app_model_logs_marker_scan_passed': logs_safe})
        assert live_state() == baseline and not telemetry['resource_guard_failed']
        emit({'phase': 'benchmark_complete', 'synthetic_only': True, 'responses': len(results),
              'final_live_state': live_state(), 'final_temporary_state': temporary_state(), **host_state()})
    except Exception as error:
        emit({'phase': 'benchmark_failed', 'error_type': type(error).__name__, 'diagnostics_withheld': True,
              'completed_cases': len(results), 'code_locations': [{'function': t.name, 'line': t.lineno}
                  for t in __import__('traceback').extract_tb(error.__traceback__)], **host_state()})
        if namespace_uid:
            try:
                emit({'phase': 'temporary_failure_state', 'temporary_state': temporary_state()})
            except Exception:
                pass
        raise RuntimeError('Isolated synthetic app test failed; inspect synthetic progress, not private data') from None
    finally:
        if monitor:
            emit({'phase': 'telemetry_after_failure', **monitor.finish()})
        for forwarding in forwards:
            forwarding.terminate()
            try:
                forwarding.wait(timeout=10)
            except subprocess.TimeoutExpired:
                forwarding.kill(); forwarding.wait(timeout=5)
        if namespace_uid:
            owned(args.owner, namespace_uid)
            kubectl('delete', 'namespace', NAMESPACE, '--wait=true', '--timeout=120s', timeout=130)
            emit({'phase': 'cleanup', 'temporary_namespace_removed': True, 'final_live_state': live_state(), **host_state()})
        PROGRESS.unlink()


if __name__ == '__main__':
    main()
