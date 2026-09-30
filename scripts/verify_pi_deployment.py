#!/usr/bin/env python3
"""Verify the scaffold on the Pi without copying kubeconfig or logging payloads.

Operator-only: never run in CI. No secrets or health records are queried.
"""
import json
import os
from pathlib import Path
import shlex
import re
import subprocess
import sys


SSH = ['ssh', '-i', str(Path.home() / '.ssh/gtrainer_pi'),
       '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
       '-o', 'StrictHostKeyChecking=yes', '-o', 'HostKeyAlias=192.168.1.232',
       '-o', 'ConnectTimeout=5', 'blondacz@192.168.1.231']


class VerificationError(ValueError):
    """Only fixed infrastructure diagnostics, never request/response payloads."""


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def validate_runtime(deployment, service, policy, pvc, ingresses):
    spec = deployment['spec']
    pod = spec['template']['spec']
    require(spec['replicas'] == 1 and spec['strategy']['type'] == 'Recreate',
            'Expected one non-overlapping app replica.')
    require(deployment['status'].get('availableReplicas') == 1, 'App is not available.')
    require(pod['nodeSelector'].get('kubernetes.io/arch') == 'arm64', 'ARM64 selector is missing.')
    require(not pod.get('hostNetwork') and not pod.get('hostPID') and not pod.get('hostIPC'),
            'Host namespace sharing is forbidden.')
    require(pod.get('automountServiceAccountToken') is False, 'App must not receive a cluster token.')
    containers = pod['containers']
    require(len(containers) == 1, 'Unexpected app sidecar.')
    for container in containers:
        require(not any(port.get('hostPort') for port in container.get('ports', [])),
                'App host ports are forbidden.')
        require(re.fullmatch(r'ghcr\.io/blondacz/gtrainer@sha256:[a-f0-9]{64}', container['image']),
                'App image must be an immutable GTrainer digest.')
        require(container['securityContext'].get('readOnlyRootFilesystem') is True,
                'Expected a read-only root filesystem.')
        require(container['securityContext'].get('allowPrivilegeEscalation') is False,
                'Privilege escalation must be disabled.')
    svc = service['spec']
    require(svc['type'] == 'ClusterIP' and not svc.get('externalIPs')
            and not svc.get('loadBalancerIP') and not svc.get('externalName')
            and not any(port.get('nodePort') for port in svc['ports']),
            'App service has an external exposure path.')
    require(not ingresses['items'], 'App namespace must not have an ingress.')
    net = policy['spec']
    require(net.get('podSelector') == {} and set(net['policyTypes']) == {'Ingress', 'Egress'}
            and not net.get('ingress') and not net.get('egress'),
            'Default-deny app isolation is missing.')
    require(pvc['status']['phase'] == 'Bound', 'Persistent storage is not bound.')
    require(pvc['metadata']['annotations'].get('kustomize.toolkit.fluxcd.io/prune') == 'disabled',
            'Storage must be protected from Flux pruning.')


def kubectl(*args):
    result = subprocess.run(SSH + [shlex.join(['sudo', '-n', 'k3s', 'kubectl', *args])],
                            capture_output=True, text=True, timeout=45)
    require(result.returncode == 0, 'Remote infrastructure query failed; diagnostics withheld.')
    return json.loads(result.stdout)


def main():
    require(not os.environ.get('GITHUB_ACTIONS'), 'CI must not connect to the home cluster.')
    for kind, name in (('gitrepository', 'flux-system'), ('kustomization', 'flux-system'),
                       ('kustomization', 'gtrainer')):
        obj = kubectl('-n', 'flux-system', 'get', kind, name, '-o', 'json')
        require(any(c['type'] == 'Ready' and c['status'] == 'True'
                    for c in obj.get('status', {}).get('conditions', [])),
                'Flux reconciliation is not Ready.')
        require(obj['status'].get('observedGeneration') == obj['metadata']['generation'],
                'Flux has not observed the current configuration.')
    def get(kind, name):
        return kubectl('-n', 'gtrainer', 'get', kind, name, '-o', 'json')
    deployment = get('deployment', 'gtrainer')
    validate_runtime(deployment, get('service', 'gtrainer'),
                     get('networkpolicy', 'gtrainer-isolation'), get('pvc', 'gtrainer-data'),
                     kubectl('-n', 'gtrainer', 'get', 'ingresses', '-o', 'json'))
    require(len(kubectl('-n', 'gtrainer', 'get', 'services', '-o', 'json')['items']) == 1,
            'Unexpected service in app namespace.')
    require(len(kubectl('-n', 'gtrainer', 'get', 'networkpolicies', '-o', 'json')['items']) == 1,
            'Unexpected policy might broaden app access.')
    print('Flux source and both reconciliations are Ready; app is available on ARM64.')
    print('Storage is Bound/protected; no direct app ingress, node port, or host port exists.')
    print('Default-deny ingress/egress is configured; live traffic tests are still required.')
    print('Configured image:', deployment['spec']['template']['spec']['containers'][0]['image'])


if __name__ == '__main__':
    try:
        main()
    except VerificationError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired):
        print('Pi deployment verification failed; payloads/credentials withheld.', file=sys.stderr)
        sys.exit(1)
