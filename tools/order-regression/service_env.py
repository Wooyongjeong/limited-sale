"""Choose which shell variables reach the experiment JVMs."""

INHERITED = ('PATH', 'HOME', 'USER', 'JAVA_HOME', 'TMPDIR', 'LANG', 'LC_ALL')


ORDER_RATE_LIMIT_VARIABLE = 'LIMITED_SALE_ORDER_RATE_LIMIT_ENABLED'


def order_rate_limit_setting(enabled):
    # The per-member request limit would answer 429 to bursts that the regression experiments rely on, so the
    # runners state the setting explicitly instead of inheriting the application default (enabled).
    return {ORDER_RATE_LIMIT_VARIABLE: 'true' if enabled else 'false'}


def inherited_environment(source):
    # Spring binds any exported variable, so a shell setting could redirect a run to a development service.
    return {name: source[name] for name in INHERITED if name in source}
