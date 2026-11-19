"""Choose which shell variables reach the experiment JVMs."""

INHERITED = ('PATH', 'HOME', 'USER', 'JAVA_HOME', 'TMPDIR', 'LANG', 'LC_ALL')


def inherited_environment(source):
    # Spring binds any exported variable, so a shell setting could redirect a run to a development service.
    return {name: source[name] for name in INHERITED if name in source}
