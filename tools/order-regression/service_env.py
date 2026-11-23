"""Choose which shell variables reach the experiment JVMs."""

INHERITED = ('PATH', 'HOME', 'USER', 'JAVA_HOME', 'TMPDIR', 'LANG', 'LC_ALL')


ORDER_RATE_LIMIT_VARIABLE = 'LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED'


def order_rate_limit_setting(enabled):
    # The per-member request limit would answer 429 to bursts that the regression experiments rely on, so the
    # runners state the setting explicitly instead of inheriting the application default (enabled).
    return {ORDER_RATE_LIMIT_VARIABLE: 'true' if enabled else 'false'}


def order_rate_limit_max_requests_setting(max_requests):
    # The application default changed from 5 to 1 after the stage 2-5 and 2-6 measurements, so runners whose results
    # were taken at 5 pass it explicitly and stay reproducible.
    return {'LIMITED_SALE_ORDER_RATE_LIMIT_MAX_REQUESTS': str(max_requests)}


def redis_timeout_settings(timeout):
    # Spring Data Redis applies the command timeout and the connect timeout separately; both take the same value.
    return {'SPRING_DATA_REDIS_TIMEOUT': timeout, 'SPRING_DATA_REDIS_CONNECT_TIMEOUT': timeout}


def tomcat_metrics_setting():
    # Tomcat registers its thread-pool MBeans, which Micrometer reads as tomcat_threads_busy_threads, only when asked.
    return {'SERVER_TOMCAT_MBEANREGISTRY_ENABLED': 'true'}


def inherited_environment(source):
    # Spring binds any exported variable, so a shell setting could redirect a run to a development service.
    return {name: source[name] for name in INHERITED if name in source}
