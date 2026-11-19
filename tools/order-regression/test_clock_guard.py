import unittest

from clock_guard import LIMIT_SECONDS, clock_errors, drift_seconds, record, record_errors, sample


def pair(wall, mono):
    return {'wall': wall, 'monotonic': mono}


class ClockGuardTest(unittest.TestCase):
    def test_sample_pairs_wall_and_monotonic_clocks(self):
        value = sample()
        self.assertEqual({'wall', 'monotonic'}, set(value))

    def test_no_drift_passes(self):
        self.assertEqual([], clock_errors(pair(1000, 50), pair(1660, 710)))

    def test_drift_above_limit_fails_and_exact_limit_passes(self):
        self.assertTrue(clock_errors(pair(1000, 50), pair(1660, 710 - LIMIT_SECONDS - 0.5)))
        self.assertEqual([], clock_errors(pair(1000, 50), pair(1660, 710 - LIMIT_SECONDS)))

    def test_suspend_like_gap_fails(self):
        self.assertTrue(clock_errors(pair(0, 0), pair(1380, 101)))

    def test_negative_drift_above_limit_fails(self):
        self.assertTrue(clock_errors(pair(1000, 0), pair(1054, 60)))
        self.assertEqual([], clock_errors(pair(1000, 0), pair(1055, 60)))

    def test_missing_or_malformed_samples_fail(self):
        good = pair(1, 1)
        for start, end in [(None, good), (good, None), ({}, good), (good, {'wall': 1}),
                           (good, pair('x', 2)), (good, pair(True, 2)), (good, pair(float('nan'), 2)), ([], good)]:
            self.assertTrue(clock_errors(start, end), (start, end))

    def test_record_holds_samples_drift_and_limit(self):
        value = record(pair(0, 0), pair(10, 7))
        self.assertEqual({'start': pair(0, 0), 'end': pair(10, 7), 'drift_seconds': 3, 'limit_seconds': LIMIT_SECONDS}, value)
        self.assertEqual(3, drift_seconds(pair(0, 0), pair(10, 7)))
        self.assertEqual([], record_errors(value))

    def test_record_errors_recompute_drift_instead_of_trusting_it(self):
        value = record(pair(0, 0), pair(1380, 101))
        self.assertTrue(record_errors(value))
        value['drift_seconds'] = 0
        self.assertTrue(record_errors(value))

    def test_missing_or_malformed_record_fails(self):
        for value in (None, [], {}, {'start': pair(0, 0)}, {'start': 1, 'end': 2}):
            self.assertTrue(record_errors(value), value)


if __name__ == '__main__':
    unittest.main()
