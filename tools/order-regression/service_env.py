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


# Keep in step with compose.yaml.
KAFKA_IMAGE = 'apache/kafka:4.4.0'


def kafka_broker_environment(port):
    # KRaft single node. One listener on the same port inside and outside the container, so the address the broker
    # advertises is the one the host JVMs dial; a Docker-chosen mapping would not match it. Automatic topic creation
    # is off as in compose.yaml: the application creates its topic.
    return {
        'KAFKA_NODE_ID': '1', 'KAFKA_PROCESS_ROLES': 'broker,controller',
        'KAFKA_LISTENERS': f'PLAINTEXT://:{port},CONTROLLER://:9093',
        'KAFKA_ADVERTISED_LISTENERS': f'PLAINTEXT://127.0.0.1:{port}',
        'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',
        'KAFKA_INTER_BROKER_LISTENER_NAME': 'PLAINTEXT', 'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER',
        'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@localhost:9093',
        'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR': '1', 'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR': '1',
        'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR': '1', 'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS': '0',
        'KAFKA_AUTO_CREATE_TOPICS_ENABLE': 'false', 'KAFKA_DEFAULT_REPLICATION_FACTOR': '1',
        'KAFKA_HEAP_OPTS': '-Xmx512m -Xms512m',
    }


def kafka_bootstrap_setting(port):
    return {'KAFKA_BOOTSTRAP_SERVERS': f'127.0.0.1:{port}'}


def tomcat_metrics_setting():
    # Tomcat registers its thread-pool MBeans, which Micrometer reads as tomcat_threads_busy_threads, only when asked.
    return {'SERVER_TOMCAT_MBEANREGISTRY_ENABLED': 'true'}


def inherited_environment(source):
    # Spring binds any exported variable, so a shell setting could redirect a run to a development service.
    return {name: source[name] for name in INHERITED if name in source}
