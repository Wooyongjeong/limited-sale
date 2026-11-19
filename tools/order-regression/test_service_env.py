import unittest

from service_env import inherited_environment


class ServiceEnvironmentTest(unittest.TestCase):
    def test_shell_settings_cannot_redirect_experiment_services(self):
        inherited = inherited_environment({
            'PATH': '/bin', 'JAVA_HOME': '/java',
            'SPRING_DATASOURCE_URL': 'jdbc:mysql://dev/limited_sale', 'SPRING_PROFILES_ACTIVE': 'dev',
            'REDIS_HOST': 'dev-redis', 'MOCK_PG_BASE_URL': 'http://dev-pg',
            'LIMITED_SALE_RECOVERY_LEASE_DURATION': 'PT1S', 'JAVA_TOOL_OPTIONS': '-Dserver.port=1'})
        self.assertEqual({'PATH': '/bin', 'JAVA_HOME': '/java'}, inherited)


if __name__ == '__main__':
    unittest.main()
