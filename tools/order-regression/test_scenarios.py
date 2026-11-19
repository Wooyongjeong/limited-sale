import unittest
from evidence import scenario_errors


class ScenarioTest(unittest.TestCase):
    def test_pg_results_match_fixed_scenario_and_delay(self):
        plan = [{'order_public_id': 'o', 'scenario': 'DELAY', 'delay_seconds': 10}]
        targets = [{'order_public_id': 'o', 'pg_key': 'k'}]
        observed = [{'pg_key': 'k', 'scenario': 'DELAY', 'delay_seconds': 10}]
        self.assertEqual([], scenario_errors(plan, targets, observed))
        observed[0]['delay_seconds'] = 5
        self.assertTrue(scenario_errors(plan, targets, observed))
        observed[0]['delay_seconds'] = 10
        observed[0]['scenario'] = 'SUCCESS'
        self.assertTrue(scenario_errors(plan, targets, observed))
        self.assertTrue(scenario_errors(plan, targets, []))


if __name__ == '__main__':
    unittest.main()
