"""Explicit fixture suites for the unchanged prepared-focus-v1 contract."""
import app_unseen_fixtures
import prepared_review as contract
import prepared_review_fresh_fixtures as fresh

SUITES = ('known-v1', 'fresh-v1')


def suite_data(suite='known-v1'):
    if suite == 'known-v1':
        cases = app_unseen_fixtures.cases()
        return cases, contract.REQUESTS, list(range(len(cases)))
    if suite == 'fresh-v1':
        cases = fresh.cases()
        return cases, fresh.REQUESTS, [c['candidate_rotation'] for c in cases]
    raise ValueError('Unknown prepared-focus fixture suite')


def rubric_hash(requests):
    return contract.digest({name: [focus, sorted(kinds)] for name, (focus, kinds) in requests.items()})
