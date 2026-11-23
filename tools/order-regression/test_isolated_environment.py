import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault('JAVA_HOME', '/unused')

from isolated import Isolated


class SalesProcessEnvironmentTest(unittest.TestCase):
    def started_environment(self, **options):
        with tempfile.TemporaryDirectory() as directory:
            env = Isolated(Path(directory) / 'out', **options)
            env.ports = {'sales1': 1, 'sales1_management': 2}
            env.sales_jar = 'sales.jar'
            env.sales_env = {'REDIS_PORT': '6379'}
            with mock.patch.object(Isolated, 'start_process', return_value=None) as start:
                env.start_sales('sales1')
            return start.call_args.args[2], env.order_rate_limit

    def test_sales_instances_start_without_the_order_rate_limit_by_default(self):
        environment, recorded = self.started_environment()
        self.assertEqual('false', environment['LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED'])
        self.assertFalse(recorded)
        self.assertEqual('6379', environment['REDIS_PORT'])

    def test_the_limit_can_be_switched_on_for_a_comparison_run(self):
        environment, recorded = self.started_environment(order_rate_limit=True)
        self.assertEqual('true', environment['LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED'])
        self.assertTrue(recorded)


if __name__ == '__main__':
    unittest.main()
