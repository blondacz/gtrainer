"""Frozen new synthetic review cases; not an external blinded holdout.

Do not edit values, ordering, focus assignments or rubric after output review.
The existing prepared-focus-v1 prompt/contract remain unchanged.
"""
from datetime import date


REQUESTS = {
    'fresh_daily_split': ('daily_combined', {'cross_metric_pattern'}),
    'fresh_wellness_stable': ('wellness', {'wellness_pattern'}),
    'fresh_mix_opposed': ('activity_balance', {'sport_mix'}),
    'fresh_gap_current_hrv': ('missing_wellness', {'coverage_gap'}),
    'fresh_daily_zero_stable': ('daily_combined', {'cross_metric_pattern'}),
    'fresh_wellness_sleep_gap': ('wellness', {'wellness_pattern'}),
    'fresh_mix_both_decrease': ('activity_balance', {'sport_mix'}),
    'fresh_gap_previous_hrv': ('missing_wellness', {'coverage_gap'}),
    'fresh_daily_zero_opposed': ('daily_combined', {'cross_metric_pattern'}),
    'fresh_daily_steady_sports': ('daily_combined', {'cross_metric_pattern'}),
}


def cases():
    definitions = [
        ('fresh_daily_split', [('Ride', 1750, 1000)], (19000, 21000), (73, 64), 2, 0),
        ('fresh_wellness_stable', [('Run', 450, 450)], (25100, 25100), (35, 35), 2, 1),
        ('fresh_mix_opposed', [('Kayaking', 800, 1200), ('Run', 1200, 800)], (25900, 25300), (49, 51), 2, 2),
        ('fresh_gap_current_hrv', [('Swim', 1800, 2300)], (26700, 24500), (71, None), 2, 3),
        ('fresh_daily_zero_stable', [('Hike', 0, 480)], (23000, 23000), (61, 61), 2, 4),
        ('fresh_wellness_sleep_gap', [('Ride', 1400, 1750)], (None, 23400), (52, 47), 2, 3),
        ('fresh_mix_both_decrease', [('Kayaking', 1300, 900), ('Ride', 3000, 2100)], (28000, 26500), (81, 75), 2, 0),
        ('fresh_gap_previous_hrv', [('Run', 2100, 2000)], (24100, 25000), (None, 57), 2, 1),
        ('fresh_daily_zero_opposed', [('Swim', 0, 750)], (26000, 24400), (45, 59), 2, 8),
        ('fresh_daily_steady_sports', [('Ride', 1700, 1700), ('Run', 600, 600)], (21300, 22300), (65, 62), 2, 9),
        ('fresh_sparse_activity', [('Hike', 600, 900)], (23500, 22000), (60, 55), 1, 0),
        ('fresh_missing_current_wellness', [('Kayaking', 1900, 1600)], (27200, None), (63, None), 2, 0),
    ]
    return [{'name': name, 'oldest': str(date(2020, month, 9)), 'newest': str(date(2020, month, 15)),
             'previousOldest': str(date(2020, month, 2)), 'previousNewest': str(date(2020, month, 8)),
             'sports': sports, 'sleep': sleep, 'hrv': hrv, 'activity_samples_per_period': count,
             'candidate_rotation': rotation, 'synthetic_only': True,
             'fixture_split': 'fresh-prepared-focus-structural-variants-v1'}
            for month, (name, sports, sleep, hrv, count, rotation) in enumerate(definitions, 1)]
