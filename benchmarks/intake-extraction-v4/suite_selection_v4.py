"""Closed selection between two source-bound suites, not a plugin interface."""
from types import SimpleNamespace


def select(profile="context-intake-extraction-v4"):
    import experiment_v4 as old
    if profile == old.PROFILE:
        import revision_v4 as revision
        import replay_v4 as replay
        import oracle_v4 as oracle
        return SimpleNamespace(profile=old.PROFILE, ids=old.IDS, verify=old.verify,
            here=old.HERE, edition="v4", revision=revision, replay=replay, oracle=oracle,
            validate=replay.kotlin_validate,
            max_batches=3, max_scored=24, max_probes=3)
    if profile == "context-intake-extraction-v5":
        import sys
        path = str(old.HERE.parent / "intake-extraction-v5")
        if path not in sys.path:
            sys.path.insert(0, path)
        import suite_v5 as suite
        import revision_v5 as revision
        import replay_v5 as replay
        return SimpleNamespace(profile=suite.PROFILE, ids=suite.IDS, verify=suite.verify,
            here=suite.HERE, edition="v5", revision=revision, replay=replay, oracle=suite,
            validate=replay.kotlin_results,
            max_batches=1, max_scored=8, max_probes=1)
    raise ValueError("Unknown intake evaluation suite")
