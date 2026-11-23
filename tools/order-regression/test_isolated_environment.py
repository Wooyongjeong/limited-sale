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

    def test_extra_settings_reach_the_sales_jvm_and_are_kept_for_the_record(self):
        environment, _ = self.started_environment(
            sales_extra_env={'SPRING_DATA_REDIS_TIMEOUT': '1s', 'SPRING_DATA_REDIS_CONNECT_TIMEOUT': '1s'})
        self.assertEqual('1s', environment['SPRING_DATA_REDIS_TIMEOUT'])
        self.assertEqual('1s', environment['SPRING_DATA_REDIS_CONNECT_TIMEOUT'])
        default, _ = self.started_environment()
        self.assertNotIn('SPRING_DATA_REDIS_TIMEOUT', default)

    def test_a_fixed_host_port_is_published_so_a_stopped_container_comes_back_on_the_same_port(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Isolated(Path(directory) / 'out')
            with mock.patch('isolated.command',
                            side_effect=lambda *args, **kw: '127.0.0.1:1' if 'port' in args else 'cid') as run, \
                    mock.patch('isolated.json.loads', return_value=[{'Mounts': []}]):
                env.container('redis', 'redis:7.4-alpine', 6379, host_port=45678)
                env.container('other', 'redis:7.4-alpine', 6379)
        published = [call.args[call.args.index('-p') + 1] for call in run.call_args_list if 'run' in call.args]
        self.assertEqual(['127.0.0.1:45678:6379', '127.0.0.1::6379'], published)

    def test_the_limit_can_be_switched_on_for_a_comparison_run(self):
        environment, recorded = self.started_environment(order_rate_limit=True)
        self.assertEqual('true', environment['LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED'])
        self.assertTrue(recorded)


if __name__ == '__main__':
    unittest.main()
