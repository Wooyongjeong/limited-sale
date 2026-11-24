import unittest

from service_env import (KAFKA_IMAGE, inherited_environment, kafka_bootstrap_setting, kafka_broker_environment,
                         order_rate_limit_max_requests_setting, order_rate_limit_setting, redis_timeout_settings,
                         tomcat_metrics_setting)


class ServiceEnvironmentTest(unittest.TestCase):
    def test_shell_settings_cannot_redirect_experiment_services(self):
        inherited = inherited_environment({
            'PATH': '/bin', 'JAVA_HOME': '/java',
            'SPRING_DATASOURCE_URL': 'jdbc:mysql://dev/limited_sale', 'SPRING_PROFILES_ACTIVE': 'dev',
            'REDIS_HOST': 'dev-redis', 'MOCK_PG_BASE_URL': 'http://dev-pg',
            'LIMITED_SALE_RECOVERY_LEASE_DURATION': 'PT1S', 'JAVA_TOOL_OPTIONS': '-Dserver.port=1'})
        self.assertEqual({'PATH': '/bin', 'JAVA_HOME': '/java'}, inherited)

    def test_a_shell_setting_cannot_switch_the_order_rate_limit_back_on(self):
        self.assertEqual({}, inherited_environment({'LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED': 'true'}))

    def test_the_order_rate_limit_is_always_stated_explicitly(self):
        self.assertEqual({'LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED': 'false'}, order_rate_limit_setting(False))
        self.assertEqual({'LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED': 'true'}, order_rate_limit_setting(True))

    def test_the_allowed_request_count_is_passed_as_a_string_the_application_binds(self):
        self.assertEqual({'LIMITED_SALE_ORDER_RATE_LIMIT_MAX_REQUESTS': '5'}, order_rate_limit_max_requests_setting(5))
        self.assertEqual({}, inherited_environment({'LIMITED_SALE_ORDER_RATE_LIMIT_MAX_REQUESTS': '1'}))

    def test_redis_timeouts_set_both_the_command_and_the_connect_timeout(self):
        self.assertEqual({'SPRING_DATA_REDIS_TIMEOUT': '1s', 'SPRING_DATA_REDIS_CONNECT_TIMEOUT': '1s'},
                         redis_timeout_settings('1s'))
        self.assertEqual({}, inherited_environment({'SPRING_DATA_REDIS_TIMEOUT': '60s'}))
        self.assertEqual({'SERVER_TOMCAT_MBEANREGISTRY_ENABLED': 'true'}, tomcat_metrics_setting())

    def test_the_isolated_broker_advertises_the_address_the_sales_jvms_dial(self):
        broker = kafka_broker_environment(45678)
        self.assertEqual('PLAINTEXT://127.0.0.1:45678', broker['KAFKA_ADVERTISED_LISTENERS'])
        self.assertTrue(broker['KAFKA_LISTENERS'].startswith('PLAINTEXT://:45678,'))
        self.assertEqual({'KAFKA_BOOTSTRAP_SERVERS': '127.0.0.1:45678'}, kafka_bootstrap_setting(45678))
        # The same broker shape as compose.yaml: one node, replication 1, the application creates its topic.
        self.assertEqual('false', broker['KAFKA_AUTO_CREATE_TOPICS_ENABLE'])
        self.assertEqual('1', broker['KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR'])
        self.assertTrue(KAFKA_IMAGE.startswith('apache/kafka:'))

    def test_a_shell_setting_cannot_redirect_the_sales_jvms_to_a_development_broker(self):
        self.assertEqual({}, inherited_environment({'KAFKA_BOOTSTRAP_SERVERS': 'dev-kafka:9092',
                                                    'SPRING_KAFKA_BOOTSTRAP_SERVERS': 'dev-kafka:9092'}))


if __name__ == '__main__':
    unittest.main()
