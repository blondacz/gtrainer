"""Explicitly synthetic version-1 records for an isolated published-app test.

Never read, copy, or mount an application database to prepare these fixtures.
This seeds normalized records, not the upstream adapter, and is not production
math: the published Kotlin application still prepares all inference input.
"""
import base64
from datetime import date
import hashlib
import json
from pathlib import Path
import sqlite3

APP_IMAGE = 'ghcr.io/blondacz/gtrainer@sha256:90b7144514c27b2fc10e8498b8789ca8f42722fe3fd365ba891ad36169ff5448'
APP_REVISION = 'f6d879a7293ca0f07d218d31f8fe96e9fa0cc6f3'
SYSTEM_PROMPT_SHA256 = '9e544322e7eb0c976dbcef4c2973b3b0859fb5e5505701d3f76c549ca558e314'
OLLAMA_IMAGE = 'ollama/ollama@sha256:0d7a1b2e50d33428f0117535a25933fa61f8868f383c8e1d07823889412c8084'
PYTHON_IMAGE = 'python@sha256:ff547c46029c9cd2dbce2f1ce5873debb58b9ccfeeac2ded49f72d15f47273e2'
MODEL = 'ministral-3:3b'
ALIAS = 'ministral-synthetic:3b'  # Same weights, not a second qualified model.
MODEL_DIGEST = 'f04aa1c738f64e13c625b82ae92504fc0260fa6723b509ed1ece0fa188179b1d'
MODEL_PROFILES = {
    MODEL: {'tag': MODEL, 'alias': ALIAS, 'digest': MODEL_DIGEST,
            'id': 'ministral-3-3b', 'label': 'Ministral 3 3B',
            'aliasId': 'ministral-synthetic-alias', 'aliasLabel': 'Ministral synthetic alias - same weights'},
    'granite4.2:3b': {'tag': 'granite4.2:3b', 'alias': 'granite42-synthetic:3b',
                     'digest': '40577dc168a3a9ad34e9a1234e0c2570be86097fa75a236d4574ae985705d3c4',
                     'id': 'granite-4-2-3b', 'label': 'IBM Granite 4.2 3B',
                     'aliasId': 'granite42-synthetic-alias', 'aliasLabel': 'Granite 4.2 synthetic alias - same weights'},
    'qwen3:4b-instruct': {'tag': 'qwen3:4b-instruct', 'alias': 'qwen3-instruct-synthetic:4b',
                         'digest': '0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0',
                         'id': 'qwen3-4b-instruct', 'label': 'Qwen3 4B Instruct-2507',
                          'aliasId': 'qwen3-instruct-synthetic-alias', 'aliasLabel': 'Qwen3 Instruct synthetic alias - same weights'},
    'qwen3.5:4b': {'tag': 'qwen3.5:4b', 'alias': 'qwen35-synthetic:4b',
                  'digest': '2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd',
                  'id': 'qwen-3-5-4b', 'label': 'Qwen3.5 4B',
                  'aliasId': 'qwen35-synthetic-alias', 'aliasLabel': 'Qwen3.5 synthetic alias - same weights'},
}
PASSWORD = 'synthetic-app-benchmark-password-not-a-real-credential'
NAMESPACE = 'gtrainer-app-benchmark'
ORIGIN = 'http://127.0.0.1:8080'
SCHEMA_SQL = '''
CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_utc TEXT NOT NULL);
CREATE TABLE activities (source TEXT NOT NULL, source_id TEXT NOT NULL, observed_date TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY(source,source_id));
CREATE TABLE wellness (source TEXT NOT NULL, source_id TEXT NOT NULL, observed_date TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY(source,source_id));
CREATE INDEX activity_dates ON activities(observed_date);
CREATE INDEX wellness_dates ON wellness(observed_date);
CREATE TABLE events (id TEXT PRIMARY KEY, start_date TEXT NOT NULL, end_date TEXT NOT NULL, sport TEXT NOT NULL, goal TEXT NOT NULL, notes TEXT);
CREATE TABLE sync_status (category TEXT PRIMARY KEY, last_attempt_utc TEXT, last_success_utc TEXT, read_status TEXT NOT NULL, rejected INTEGER NOT NULL DEFAULT 0, incomplete INTEGER NOT NULL DEFAULT 0);
INSERT INTO schema_migrations VALUES (1,'2020-01-01T00:00:00Z');
INSERT INTO sync_status VALUES ('activities','2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','SUCCESS',0,0);
INSERT INTO sync_status VALUES ('wellness','2020-01-01T00:00:00Z','2020-01-01T00:00:00Z','SUCCESS',0,0);
PRAGMA user_version=1;
'''


def cases():
    definitions = [
        ('cross_metric', [('Ride', 5400, 3600)], (27000, 23400), (50, 40), 2),
        ('mixed_directions', [('Ride', 3600, 5400)], (27000, 23400), (40, 40), 2),
        ('mixed_sports', [('Ride', 3600, 1800), ('Run', 1800, 3600)], (27000, 23400), (40, 40), 2),
        ('partial_hrv', [('Ride', 5400, 3600)], (27000, 23400), (50, None), 2),
        ('zero_baseline', [('Swim', 0, 900)], (27000, 27000), (50, 50), 2),
        ('unchanged', [('Run', 1800, 1800)], (27000, 27000), (50, 50), 2),
        ('sparse_activity', [('Ride', 5400, 3600)], (27000, 23400), (50, 40), 1),
        ('missing_current_wellness', [('Ride', 5400, 3600)], (27000, None), (50, None), 2),
    ]
    result = []
    for month, (name, sports, sleep, hrv, count) in enumerate(definitions, 1):
        result.append({'name': name, 'oldest': str(date(2020, month, 8)), 'newest': str(date(2020, month, 14)),
                       'previousOldest': str(date(2020, month, 1)), 'previousNewest': str(date(2020, month, 7)),
                       'sports': sports, 'sleep': sleep, 'hrv': hrv, 'activity_samples_per_period': count,
                       'synthetic_only': True})
    return result


def records(fixture_cases=None):
    activities, wellness = [], []
    for case in cases() if fixture_cases is None else fixture_cases:
        assert case['synthetic_only'] is True
        for period in (0, 1):
            dates = [case['previousOldest'], case['previousNewest']] if period == 0 else [case['oldest'], case['newest']]
            for sport, before, after in case['sports']:
                for index, day in enumerate(dates[:case['activity_samples_per_period']]):
                    moving = before if period == 0 else after
                    activities.append({'source': 'synthetic-fixture', 'sourceRecordId': f"synthetic-{case['name']}-{sport}-{period}-{index}",
                        'upstreamSources': ['SYNTHETIC'], 'sport': sport, 'startLocal': day + 'T10:00:00',
                        'startInstant': day + 'T10:00:00Z', 'timeZone': 'UTC', 'sourceTimeZoneLabel': 'UTC',
                        'utcOffset': 'Z', 'timeContext': 'named_zone', 'movingTime': {'value': moving, 'unit': 'seconds'},
                        'elapsedTime': {'value': moving + 300, 'unit': 'seconds'}, 'calories': {'value': 100, 'unit': 'kcal'},
                        'distance': None, 'averageHeartRate': None, 'intervalsTrainingLoad': None})
            for day in dates:
                measurements = {'weight': {'value': 70, 'unit': 'kg'}}
                for metric, values, unit in (('sleepSecs', case['sleep'], 'seconds'), ('hrv', case['hrv'], 'ms')):
                    if values[period] is not None:
                        measurements[metric] = {'value': values[period], 'unit': unit}
                wellness.append({'source': 'synthetic-fixture', 'sourceRecordId': 'synthetic-wellness-' + day,
                                 'date': day, 'upstreamSources': ['SYNTHETIC'], 'measurements': measurements})
    return activities, wellness


def database(path, fixture_cases=None):
    """Exclusively create a synthetic database; never open an existing path."""
    path = Path(path)
    with path.open('xb'):
        pass
    path.chmod(0o600)
    with sqlite3.connect(path) as connection:
        connection.executescript(SCHEMA_SQL)
        for table, items in zip(('activities', 'wellness'), records(fixture_cases)):
            for item in items:
                day = item['startLocal'][:10] if table == 'activities' else item['date']
                connection.execute(f'INSERT INTO {table} VALUES (?,?,?,?)',
                    (item['source'], item['sourceRecordId'], day, json.dumps(item, separators=(',', ':'), allow_nan=False)))
        assert connection.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'


def verifier():
    encode = lambda value: base64.urlsafe_b64encode(value).decode().rstrip('=')
    salt = bytes(range(16))
    value = hashlib.pbkdf2_hmac('sha256', PASSWORD.encode(), salt, 600_000, dklen=32)
    return f'pbkdf2-sha256$600000${encode(salt)}${encode(value)}'


def model_profile(candidate=MODEL):
    if candidate not in MODEL_PROFILES:
        raise ValueError('Only an explicitly supported synthetic candidate may be staged')
    return MODEL_PROFILES[candidate].copy()


def catalogue(candidate=MODEL):
    profile = model_profile(candidate)
    return {'endpoint': 'http://127.0.0.1:11434', 'models': [
        {'id': identifier, 'label': label, 'tag': tag, 'digest': profile['digest'], 'experimental': True}
        for identifier, label, tag in ((profile['id'], profile['label'], profile['tag']),
                                     (profile['aliasId'], profile['aliasLabel'], profile['alias']))]}
