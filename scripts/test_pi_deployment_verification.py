import copy
import unittest

from verify_pi_deployment import validate_runtime


class RuntimeVerification(unittest.TestCase):
    def setUp(self):
        self.deployment = {'spec': {'replicas': 1, 'strategy': {'type': 'Recreate'},
                           'template': {'spec': {'nodeSelector': {'kubernetes.io/arch': 'arm64'},
                           'automountServiceAccountToken': False,
                           'containers': [{'image': 'ghcr.io/blondacz/gtrainer@sha256:' + 'a' * 64, 'securityContext': {
                               'readOnlyRootFilesystem': True, 'allowPrivilegeEscalation': False}}]}}},
                           'status': {'availableReplicas': 1}}
        self.service = {'spec': {'type': 'ClusterIP', 'ports': [{'port': 8080}]}}
        self.policy = {'spec': {'podSelector': {}, 'policyTypes': ['Ingress', 'Egress']}}
        self.pvc = {'status': {'phase': 'Bound'}, 'metadata': {'annotations': {
            'kustomize.toolkit.fluxcd.io/prune': 'disabled'}}}

    def verify(self, deployment=None, service=None, policy=None, pvc=None, ingresses=None):
        validate_runtime(deployment or self.deployment, service or self.service,
                         policy or self.policy, pvc or self.pvc, ingresses or {'items': []})

    def test_isolated_scaffold_passes(self):
        self.verify()

    def test_external_service_paths_are_rejected(self):
        for field, value in [('type', 'NodePort'), ('type', 'LoadBalancer'),
                             ('externalIPs', ['203.0.113.1']), ('ports', [{'nodePort': 30080}])]:
            service = copy.deepcopy(self.service)
            service['spec'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify(service=service)

    def test_host_network_or_host_port_is_rejected(self):
        for field in ('hostNetwork', 'hostPID', 'hostIPC'):
            deployment = copy.deepcopy(self.deployment)
            deployment['spec']['template']['spec'][field] = True
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify(deployment=deployment)
        deployment = copy.deepcopy(self.deployment)
        deployment['spec']['template']['spec']['containers'][0]['ports'] = [{'hostPort': 8080}]
        with self.assertRaises(ValueError):
            self.verify(deployment=deployment)

    def test_allow_policy_or_ingress_is_rejected(self):
        policy = copy.deepcopy(self.policy)
        policy['spec']['ingress'] = [{}]
        with self.assertRaises(ValueError):
            self.verify(policy=policy)
        with self.assertRaises(ValueError):
            self.verify(ingresses={'items': [{'kind': 'Ingress'}]})

    def test_unbound_or_unprotected_storage_is_rejected(self):
        pvc = copy.deepcopy(self.pvc)
        pvc['status']['phase'] = 'Pending'
        with self.assertRaises(ValueError):
            self.verify(pvc=pvc)
        pvc = copy.deepcopy(self.pvc)
        pvc['metadata']['annotations'] = {}
        with self.assertRaises(ValueError):
            self.verify(pvc=pvc)


if __name__ == '__main__':
    unittest.main()
