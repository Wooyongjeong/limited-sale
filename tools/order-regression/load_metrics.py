"""Pure parsing and differencing of Prometheus scrapes and scripts/mysql-observe.sh snapshots."""
import re

LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')
SAMPLE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)(?:\s+\S+)?$')
NUMERIC = re.compile(r'^-?\d+(\.\d+)?$')

# Series read repeatedly while the load runs; filtering keeps each in-memory scrape small.
SAMPLED_PREFIXES = ('hikaricp_connections', 'process_cpu_usage', 'system_cpu_usage', 'jvm_threads_live',
                    'jvm_memory_used_bytes', 'tomcat_threads')
EXCLUDED_URI_PREFIX = '/actuator'


def parse_prometheus(text, prefixes=None):
    """Return {(name, sorted label pairs): value} for each sample line; comments are skipped."""
    samples = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        found = SAMPLE.match(line)
        if not found or (prefixes and not found.group(1).startswith(prefixes)):
            continue
        labels = tuple(sorted((key, value.replace('\\"', '"').replace('\\\\', '\\'))
                               for key, value in LABEL.findall(found.group(2) or '')))
        try:
            samples[(found.group(1), labels)] = float(found.group(3))
        except ValueError:
            continue
    return samples


def total(samples, name, **labels):
    """Sum of every series of `name` whose labels include the given pairs."""
    wanted = set(labels.items())
    return sum(value for (series, pairs), value in samples.items()
               if series == name and wanted <= set(pairs) and value == value)


def by_label(samples, name, label):
    """Sum of `name` per value of one label."""
    result = {}
    for (series, pairs), value in samples.items():
        if series == name and value == value:
            for key, text in pairs:
                if key == label:
                    result[text] = result.get(text, 0) + value
    return result


def delta(before, after, name, **labels):
    return total(after, name, **labels) - total(before, name, **labels)


def peak(samples_list, name, **labels):
    """Largest value of `name` over the scrapes taken while the load ran; None when the series never appeared."""
    return max((total(sample, name, **labels) for sample in samples_list
                if any(series == name for series, _ in sample)), default=None)


def mean(samples_list, name, **labels):
    values = [total(sample, name, **labels) for sample in samples_list if any(series == name for series, _ in sample)]
    return round(sum(values) / len(values), 4) if values else None


def _mean_ms(seconds, count):
    return round(seconds * 1000 / count, 3) if count else None


def http_requests(before, after):
    """Public-port request count and mean latency deltas per method, URI and status."""
    rows = {}
    for (name, pairs), count in after.items():
        if name != 'http_server_requests_seconds_count':
            continue
        labels = dict(pairs)
        if labels.get('uri', '').startswith(EXCLUDED_URI_PREFIX):
            continue
        key = (labels.get('method', ''), labels.get('uri', ''), labels.get('status', ''))
        row = rows.setdefault(key, {'count': 0, 'seconds': 0})
        row['count'] += count - before.get((name, pairs), 0)
        row['seconds'] += (after.get(('http_server_requests_seconds_sum', pairs), 0)
                           - before.get(('http_server_requests_seconds_sum', pairs), 0))
    return [{'method': method, 'uri': uri, 'status': status, 'count': int(row['count']),
             'mean_ms': _mean_ms(row['seconds'], row['count'])}
            for (method, uri, status), row in sorted(rows.items()) if row['count'] > 0]


def management_summary(before, after, samples):
    """One instance's counter deltas over the load, plus gauge peaks seen in the periodic scrapes."""
    acquire_count = delta(before, after, 'hikaricp_connections_acquire_seconds_count')
    acquire_seconds = delta(before, after, 'hikaricp_connections_acquire_seconds_sum')
    gc_count = delta(before, after, 'jvm_gc_pause_seconds_count')
    gc_seconds = delta(before, after, 'jvm_gc_pause_seconds_sum')
    return {
        'http_server': http_requests(before, after),
        'hikari': {'acquire_count': int(acquire_count), 'acquire_mean_ms': _mean_ms(acquire_seconds, acquire_count),
                   'acquire_max_ms_peak': _scaled(peak(samples, 'hikaricp_connections_acquire_seconds_max'), 1000),
                   'active_peak': peak(samples, 'hikaricp_connections_active'),
                   'pending_peak': peak(samples, 'hikaricp_connections_pending'),
                   'max_connections': total(after, 'hikaricp_connections_max')},
        'jvm': {'gc_pause_count': int(gc_count), 'gc_pause_seconds': round(gc_seconds, 4),
                'heap_used_bytes_peak': peak(samples, 'jvm_memory_used_bytes', area='heap'),
                'threads_live_peak': peak(samples, 'jvm_threads_live_threads'),
                'tomcat_threads_busy_peak': peak(samples, 'tomcat_threads_busy_threads'),
                'tomcat_threads_max': total(after, 'tomcat_threads_config_max_threads') or None,
                'cpu_seconds': _cpu_seconds(before, after),
                'process_cpu_usage_peak': peak(samples, 'process_cpu_usage'),
                'process_cpu_usage_mean': mean(samples, 'process_cpu_usage'),
                'system_cpu_usage_peak': peak(samples, 'system_cpu_usage'),
                'system_cpu_usage_mean': mean(samples, 'system_cpu_usage')},
        'business': {'order_creation': _results(before, after, 'order_creation_results_total'),
                     'order_payment': _results(before, after, 'order_payment_results_total'),
                     'order_unfinished_after': total(after, 'order_unfinished'),
                     'order_requires_review_after': total(after, 'order_requires_review')},
        'samples': len(samples),
    }


def _cpu_seconds(before, after):
    if not any(series == 'process_cpu_time_ns_total' for series, _ in after):
        return None
    return round(delta(before, after, 'process_cpu_time_ns_total') / 1e9, 2)


def _scaled(value, factor):
    return None if value is None else round(value * factor, 3)


def _results(before, after, name):
    after_by = by_label(after, name, 'result')
    before_by = by_label(before, name, 'result')
    return {result: int(count - before_by.get(result, 0)) for result, count in sorted(after_by.items())
            if count - before_by.get(result, 0)}


def combine_management(instances):
    """Merge per-instance summaries: counts add up, peaks take the largest value, means are weighted."""
    rows = {}
    for summary in instances.values():
        for row in summary['http_server']:
            key = (row['method'], row['uri'], row['status'])
            merged = rows.setdefault(key, {'count': 0, 'weighted': 0})
            merged['count'] += row['count']
            merged['weighted'] += row['count'] * (row['mean_ms'] or 0)
    acquire = sum(s['hikari']['acquire_count'] for s in instances.values())
    weighted = sum(s['hikari']['acquire_count'] * (s['hikari']['acquire_mean_ms'] or 0) for s in instances.values())

    def largest(section, field):
        values = [s[section][field] for s in instances.values() if s[section][field] is not None]
        return max(values) if values else None

    def summed(section):
        result = {}
        for summary in instances.values():
            for result_name, count in summary['business'][section].items():
                result[result_name] = result.get(result_name, 0) + count
        return result

    return {
        'http_server': [{'method': m, 'uri': u, 'status': st, 'count': row['count'],
                         'mean_ms': round(row['weighted'] / row['count'], 3) if row['count'] else None}
                        for (m, u, st), row in sorted(rows.items())],
        'hikari': {'acquire_count': acquire, 'acquire_mean_ms': round(weighted / acquire, 3) if acquire else None,
                   'acquire_max_ms_peak': largest('hikari', 'acquire_max_ms_peak'),
                   'active_peak': largest('hikari', 'active_peak'), 'pending_peak': largest('hikari', 'pending_peak'),
                   'max_connections': largest('hikari', 'max_connections')},
        'jvm': {'gc_pause_count': sum(s['jvm']['gc_pause_count'] for s in instances.values()),
                'gc_pause_seconds': round(sum(s['jvm']['gc_pause_seconds'] for s in instances.values()), 4),
                'heap_used_bytes_peak': largest('jvm', 'heap_used_bytes_peak'),
                'threads_live_peak': largest('jvm', 'threads_live_peak'),
                'tomcat_threads_busy_peak': largest('jvm', 'tomcat_threads_busy_peak'),
                'cpu_seconds': _sum_present([s['jvm']['cpu_seconds'] for s in instances.values()]),
                'process_cpu_usage_peak': largest('jvm', 'process_cpu_usage_peak'),
                'system_cpu_usage_peak': largest('jvm', 'system_cpu_usage_peak'),
                'system_cpu_usage_mean': _mean_present([s['jvm']['system_cpu_usage_mean']
                                                        for s in instances.values()])},
        'business': {'order_creation': summed('order_creation'), 'order_payment': summed('order_payment')},
    }


def _sum_present(values):
    present = [v for v in values if v is not None]
    return round(sum(present), 2) if present else None


def _mean_present(values):
    present = [v for v in values if v is not None]
    return round(sum(present) / len(present), 4) if present else None


def endpoint_requests(summary, method, uri):
    """Requests the instance's public port saw for one endpoint, all statuses together."""
    return sum(row['count'] for row in summary['http_server'] if row['method'] == method and row['uri'] == uri)


# ---- scripts/mysql-observe.sh ----

def _value(text):
    return (float(text) if '.' in text else int(text)) if NUMERIC.match(text) else text


def parse_observe(text):
    """Split the snapshot into sections of dict rows; a section without output has no rows."""
    sections, title, lines = {}, None, []

    def close():
        if title is None:
            return
        header = lines[0].split('\t') if lines and not lines[0].startswith('(section unavailable') else []
        rows = [dict(zip(header, (_value(cell) for cell in line.split('\t')))) for line in lines[1:]]
        sections[title] = {'rows': rows, 'unavailable': bool(lines and lines[0].startswith('(section unavailable'))}

    for line in text.splitlines():
        if line.startswith('== ') and line.endswith(' =='):
            close()
            title, lines = line[3:-3], []
        elif title is not None and line:
            lines.append(line)
    close()
    return sections


def _section(snapshot, prefix):
    for title, section in snapshot.items():
        if title.startswith(prefix):
            return section
    return {'rows': [], 'unavailable': True}


def _counters(snapshot, prefix):
    return {row['counter']: row['value'] for row in _section(snapshot, prefix)['rows']}


DIGEST_SUMS = ('calls', 'total_ms', 'lock_ms', 'rows_examined', 'rows_sent', 'no_index_used', 'errors')


def _digests(snapshot):
    merged = {}
    for row in _section(snapshot, 'top ')['rows']:
        entry = merged.setdefault((row['schema_name'], row['digest_text']),
                                  {name: 0 for name in DIGEST_SUMS} | {'max_ms': 0})
        for name in DIGEST_SUMS:
            entry[name] += row[name]
        entry['max_ms'] = max(entry['max_ms'], row['max_ms'])
    return merged


def observe_delta(before, after, top=10):
    """Row-lock and deadlock counter deltas plus the statement digests that gained the most time."""
    lock_before = _counters(before, 'InnoDB row lock')
    lock_after = _counters(after, 'InnoDB row lock')
    waits = int(lock_after.get('Innodb_row_lock_waits', 0)) - int(lock_before.get('Innodb_row_lock_waits', 0))
    wait_ms = int(lock_after.get('Innodb_row_lock_time', 0)) - int(lock_before.get('Innodb_row_lock_time', 0))
    other_before = _counters(before, 'InnoDB deadlocks')
    other_after = _counters(after, 'InnoDB deadlocks')
    digests_before = _digests(before)
    changed = []
    for (schema, text), entry in _digests(after).items():
        base = digests_before.get((schema, text), {})
        gained = {name: round(entry[name] - base.get(name, 0), 3) for name in DIGEST_SUMS}
        if gained['calls'] > 0:
            changed.append({'schema': schema, 'digest_text': text, **gained,
                            'avg_ms': round(gained['total_ms'] / gained['calls'], 3), 'max_ms': entry['max_ms']})
    changed.sort(key=lambda row: row['total_ms'], reverse=True)
    return {
        'row_lock_waits': waits, 'row_lock_time_ms': wait_ms,
        'row_lock_wait_avg_ms': round(wait_ms / waits, 3) if waits else None,
        'row_lock_time_max_ms_after': lock_after.get('Innodb_row_lock_time_max'),
        'row_lock_current_waits_after': lock_after.get('Innodb_row_lock_current_waits'),
        'deadlocks': int(other_after.get('lock_deadlocks', 0)) - int(other_before.get('lock_deadlocks', 0)),
        'lock_timeouts': int(other_after.get('lock_timeouts', 0)) - int(other_before.get('lock_timeouts', 0)),
        'top_digests': changed[:top],
        'unavailable_sections': sorted(title for title, section in {**before, **after}.items()
                                       if section['unavailable']),
    }
