"""GitHub calls with scoped job token via stdin/environment, never argv/logs."""
import io
import json
import subprocess
import zipfile

from release_evidence import REPOSITORY, require, validate_evidence


PREFIX = f'repos/{REPOSITORY}'


def api(path, method='GET', payload=None, raw=False):
    command = ['gh', 'api', '--method', method, path]
    if payload is not None:
        command += ['--input', '-']
    result = subprocess.run(command, input=None if payload is None else json.dumps(payload).encode(),
                            capture_output=True, timeout=45)
    require(result.returncode == 0, 'GitHub release request failed; diagnostics withheld.')
    if raw:
        return result.stdout
    decoded = json.loads(result.stdout) if result.stdout.strip() else None
    require(not (path == 'graphql' and isinstance(decoded, dict) and decoded.get('errors')),
            'GitHub GraphQL release operation was rejected; details withheld.')
    return decoded


def successful_publication(evidence):
    run = api(f'{PREFIX}/actions/runs/{evidence["run_id"]}')
    require(run['repository']['full_name'] == REPOSITORY
            and run['head_repository']['full_name'] == REPOSITORY
            and run['head_branch'] == 'main' and run['head_sha'] == evidence['source_sha']
            and run['path'] == '.github/workflows/build.yml'
            and run['event'] in ('push', 'workflow_dispatch'),
            'Release evidence does not identify a trusted main build.')
    # Promotion can be running while this is checked: validate the prerequisites,
    # not the overall run conclusion. Never accept only a successful build job.
    jobs = api(f'{PREFIX}/actions/runs/{evidence["run_id"]}/jobs?filter=latest&per_page=100')['jobs']
    conclusions = {job['name']: job['conclusion'] for job in jobs}
    require(all(conclusions.get(name) == 'success' for name in
                ('Tests and build', 'Verify release provenance', 'Publish and verify ARM64 image')),
            'Release requires successful tests, provenance, and published ARM64 execution.')


def artifact_evidence(evidence):
    artifacts = api(f'{PREFIX}/actions/runs/{evidence["run_id"]}/artifacts?per_page=100')['artifacts']
    matches = [a for a in artifacts if a['name'] == 'verified-arm64-release' and not a['expired']]
    require(len(matches) == 1, 'Unique unexpired verified-release artifact is required.')
    artifact = matches[0]
    require(artifact['size_in_bytes'] < 16384, 'Release artifact exceeds the metadata-only size bound.')
    data = api(f'{PREFIX}/actions/artifacts/{artifact["id"]}/zip', raw=True)
    require(len(data) < 16384, 'Downloaded release artifact is too large.')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(archive.namelist() == ['verified-image.json'], 'Unexpected artifact files.')
        member = archive.getinfo('verified-image.json')
        require(member.file_size < 4096, 'Release metadata exceeds its size bound.')
        recorded = validate_evidence(json.loads(archive.read(member)))
    require(recorded == evidence, 'Release descriptor differs from successful publication evidence.')
