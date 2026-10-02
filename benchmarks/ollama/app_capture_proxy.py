"""Transparent, loopback-only instrument for the explicitly synthetic app pod.

Not a production proxy/logging component. It records exact synthetic request and
response bytes for independent inspection, without changing normal Ollama data.
Isolated faults are separately labeled and never counted as model responses.
"""
import base64
from datetime import datetime, timezone
import hashlib
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
import time

CAPTURE = Path('/capture/chat.jsonl')
FAULT = Path('/capture/synthetic-fault')
READY = Path('/capture/ready')
PROMPT_ARM = Path('/capture/synthetic-prompt-arm')
SKILL_FILE = Path('/fixtures/skill.txt')
CONTROL_SKILL_FILE = Path('/fixtures/control-skill.txt')
LOCK = threading.Lock()
LIMIT = 131072


def capture(value):
    with LOCK:
        with CAPTURE.open('a', encoding='utf-8') as output:
            output.write(json.dumps(value, allow_nan=False) + '\n')


class Proxy(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # No HTTP/header/body diagnostics, even for synthetic auth failures.

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        if (self.command, self.path) not in (('GET', '/api/version'), ('GET', '/api/tags'), ('POST', '/api/chat')):
            self.send_error(403)
            return
        if self.headers.get('Authorization') or self.headers.get('Cookie') or self.headers.get('Transfer-Encoding'):
            self.send_error(403)
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= 32768:
                raise ValueError('Bounded synthetic request required')
            body = self.rfile.read(size)
            if len(body) != size:
                raise ValueError('Incomplete request')
            request_id = hashlib.sha256(body).hexdigest() + '-' + str(time.monotonic_ns())
            fault = FAULT.read_text().strip() if FAULT.exists() else None
            if fault not in (None, 'outage', 'unsupported-output'):
                raise ValueError('Unknown synthetic fault')
            is_chat = self.path == '/api/chat'
            if is_chat:
                capture({'event': 'request', 'id': request_id, 'synthetic_only': True,
                         'started_utc': datetime.now(timezone.utc).isoformat(), 'fault': fault,
                          'body_sha256': hashlib.sha256(body).hexdigest(), 'body_base64': base64.b64encode(body).decode()})
            if is_chat and os.environ.get('GTRAINER_SYNTHETIC_PROMPT_EXPERIMENT') == '1' and fault is None:
                from app_prompt_experiment import rewrite_body
                arm = PROMPT_ARM.read_text().strip()
                control = CONTROL_SKILL_FILE.read_text() if os.environ.get('GTRAINER_SYNTHETIC_CONTROL_SKILL') == '1' else None
                body = rewrite_body(body, arm, SKILL_FILE.read_text(), control)
                capture({'event': 'forwarded_request', 'id': request_id, 'synthetic_only': True,
                         'prompt_arm': arm, 'explicit_prompt_experiment': True,
                         'body_sha256': hashlib.sha256(body).hexdigest(), 'body_base64': base64.b64encode(body).decode()})
            start = time.monotonic()
            if fault == 'outage':
                status, response = 503, b'{"error":"Injected synthetic outage; no inference"}'
            elif fault == 'unsupported-output' and is_chat:
                payload = json.loads(body)
                status = 200
                response = json.dumps({'model': payload['model'], 'done': True, 'done_reason': 'stop',
                    'message': {'role': 'assistant', 'content': 'Garmin fitness age is 21. You must sprint daily.'}}).encode()
            else:
                upstream = HTTPConnection('127.0.0.1', 11438, timeout=145)
                try:
                    upstream.request(self.command, self.path, body=body, headers={'Content-Type': 'application/json'})
                    reply = upstream.getresponse()
                    status, response = reply.status, reply.read(LIMIT + 1)
                    if len(response) > LIMIT:
                        raise ValueError('Oversized response')
                finally:
                    upstream.close()
            if is_chat:
                capture({'event': 'response', 'id': request_id, 'synthetic_only': True, 'fault': fault,
                         'status': status, 'wall_seconds': round(time.monotonic() - start, 3),
                         'body_sha256': hashlib.sha256(response).hexdigest(), 'body_base64': base64.b64encode(response).decode()})
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(response)))
            self.end_headers()
            self.wfile.write(response)
        except (OSError, ValueError, TimeoutError):
            # A timed-out/canceled caller may close its connection first.
            try:
                self.send_error(502, 'Synthetic instrument unavailable')
            except OSError:
                pass


def main():
    if os.environ.get('GTRAINER_SYNTHETIC_BENCHMARK') != '1':
        raise RuntimeError('Only the explicitly synthetic benchmark may start capture')
    # Refuse reuse of captures/faults from any previous run.
    descriptor = os.open(CAPTURE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    if FAULT.exists():
        raise RuntimeError('Unexpected existing synthetic fault')
    server = ThreadingHTTPServer(('127.0.0.1', 11434), Proxy)
    # Binding succeeded; avoid a fresh, CPU-throttled Python/SSL import in every
    # one-second kubelet exec probe. The runner separately verifies transport.
    descriptor = os.open(READY, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    server.serve_forever()


if __name__ == '__main__':
    main()
