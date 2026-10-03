"""Frozen fresh synthetic variants for comparing card v1 with v2, not real health data.

Known task structure, new dates/sports/values/direction combinations and missing
sleep cases. These are unseen by the tested local model, not a blinded external
evaluation or proof of physiological reasoning. Do not tune after inspecting outputs.
"""
from datetime import date


def cases():
    definitions = [
        ('unseen_opposing_sleep_up', [('Run', 1500, 2400)], (22000, 26000), (60, 45), 2),
        ('unseen_steady_with_hrv_drop', [('Swim', 2100, 2100)], (26400, 26400), (58, 43), 2),
        ('unseen_two_sports_both_increase', [('Hike', 900, 1500), ('Ride', 2700, 3300)], (24600, 25200), (62, 66), 2),
        ('unseen_two_sports_sleep_unknown', [('Run', 2700, 1800), ('Swim', 900, 1500)], (None, None), (44, 52), 2),
        ('unseen_sleep_previous_unknown', [('Hike', 2400, 1800)], (None, 25200), (56, 56), 2),
        ('unseen_zero_with_hrv_up', [('Ride', 0, 600)], (24000, 24600), (48, 56), 2),
        ('sparse_activity', [('Run', 1500, 2400)], (22000, 26000), (60, 45), 1),
        ('missing_current_wellness', [('Swim', 2100, 1800)], (26400, None), (58, None), 2),
    ]
    return [{'name': name, 'oldest': str(date(2020, month, 22)), 'newest': str(date(2020, month, 28)),
             'previousOldest': str(date(2020, month, 15)), 'previousNewest': str(date(2020, month, 21)),
             'sports': sports, 'sleep': sleep, 'hrv': hrv, 'activity_samples_per_period': count,
             'synthetic_only': True, 'fixture_split': 'fresh-structural-variants-v1'}
            for month, (name, sports, sleep, hrv, count) in enumerate(definitions, 1)]
