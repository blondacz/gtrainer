"""Regression checks complement actual Kustomize/API-server/live verification."""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'deploy/gtrainer'


class DeploymentGuards(unittest.TestCase):
    def test_no_direct_network_exposure(self):
        manifests = '\n'.join(path.read_text() for path in APP.glob('*.yaml'))
        self.assertNotRegex(manifests, r'kind:\s*(Ingress|IngressRoute|Gateway|HTTPRoute)\b')
        self.assertNotRegex(manifests, r'type:\s*(LoadBalancer|NodePort)\b')
        self.assertNotRegex(manifests, re.compile(r'^\s*(hostNetwork|hostPort|nodePort|externalIPs):', re.MULTILINE))
        service = (APP / 'service.yaml').read_text()
        self.assertIn('type: ClusterIP', service)
        policy = (APP / 'network-policy.yaml').read_text()
        self.assertIn('podSelector: {}', policy)
        self.assertIn('policyTypes: [Ingress, Egress]', policy)
        self.assertIn('ingress: []', policy)
        self.assertIn('egress: []', policy)

    def test_image_resources_health_and_storage_are_pinned(self):
        deployment = (APP / 'deployment.yaml').read_text()
        images = re.findall(r'^\s*image: (.+)$', deployment, re.MULTILINE)
        self.assertEqual(len(images), 1)
        self.assertRegex(images[0], r'^ghcr\.io/blondacz/gtrainer@sha256:[0-9a-f]{64}$')
        for expected in ('replicas: 1', 'type: Recreate', 'kubernetes.io/arch: arm64',
                         'automountServiceAccountToken: false', 'runAsNonRoot: true',
                         'runAsUser: 10001', 'readOnlyRootFilesystem: true',
                         'allowPrivilegeEscalation: false', 'drop: [ALL]',
                         'type: RuntimeDefault', 'memory: 1Gi', 'cpu: "2"',
                         'claimName: gtrainer-data', 'mountPath: /data', 'sizeLimit: 64Mi'):
            self.assertIn(expected, deployment)
        for probe in ('startupProbe:', 'readinessProbe:', 'livenessProbe:'):
            self.assertIn(probe, deployment)
        self.assertEqual(deployment.count('path: /healthz'), 3)
        pvc = (APP / 'storage.yaml').read_text()
        self.assertIn('kustomize.toolkit.fluxcd.io/prune: disabled', pvc)
        self.assertIn('storage: 2Gi', pvc)

    def test_flux_uses_public_outbound_source_and_scoped_app_reconciler(self):
        sync = (ROOT / 'clusters/pi/flux-system/gotk-sync.yaml').read_text()
        self.assertIn('url: https://github.com/blondacz/gtrainer.git', sync)
        self.assertIn('branch: main', sync)
        self.assertNotIn('secretRef:', sync)
        app_sync = (ROOT / 'clusters/pi/gtrainer-sync.yaml').read_text()
        self.assertIn('serviceAccountName: gtrainer-reconciler', app_sync)
        self.assertIn('wait: true', app_sync)
        self.assertIn('path: ./deploy/gtrainer', app_sync)
        install = (ROOT / 'clusters/pi/flux-system/kustomization.yaml').read_text()
        self.assertIn('/releases/download/v2.9.5/install.yaml', install)
        self.assertNotIn('latest', '\n'.join(line for line in install.splitlines() if not line.lstrip().startswith('#')))

    def test_tunnel_binds_both_ends_to_loopback_and_verifies_host(self):
        tunnel = (ROOT / 'scripts/pi-tunnel.sh').read_text()
        self.assertIn('-L 127.0.0.1:8080:127.0.0.1:18080', tunnel)
        self.assertIn('--address=127.0.0.1', tunnel)
        self.assertIn('StrictHostKeyChecking=yes', tunnel)
        self.assertIn('ExitOnForwardFailure=yes', tunnel)
        self.assertNotIn('0.0.0.0', tunnel)


if __name__ == '__main__':
    unittest.main()
