import unittest

from load_metrics import (combine_management, delta, endpoint_requests, management_summary, mean, observe_delta,
                          parse_observe, parse_prometheus, peak, total)

BEFORE = '''# HELP http_server_requests_seconds Server requests
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_count{error="none",method="POST",outcome="SUCCESS",status="201",uri="/api/v1/orders"} 2.0
http_server_requests_seconds_sum{error="none",method="POST",outcome="SUCCESS",status="201",uri="/api/v1/orders"} 0.2
http_server_requests_seconds_count{error="none",method="GET",outcome="SUCCESS",status="200",uri="/actuator/health"} 9.0
http_server_requests_seconds_sum{error="none",method="GET",outcome="SUCCESS",status="200",uri="/actuator/health"} 9.0
hikaricp_connections_acquire_seconds_count{pool="HikariPool-1"} 10.0
hikaricp_connections_acquire_seconds_sum{pool="HikariPool-1"} 0.1
order_creation_results_total{result="created"} 2.0
order_creation_results_total{result="sale_sold_out"} 0.0
jvm_gc_pause_seconds_count{action="end of minor GC",cause="G1 Evacuation Pause"} 1.0
jvm_gc_pause_seconds_sum{action="end of minor GC",cause="G1 Evacuation Pause"} 0.01
process_cpu_time_ns_total 3.0E9
'''
AFTER = '''http_server_requests_seconds_count{error="none",method="POST",outcome="SUCCESS",status="201",uri="/api/v1/orders"} 12.0
http_server_requests_seconds_sum{error="none",method="POST",outcome="SUCCESS",status="201",uri="/api/v1/orders"} 2.2
http_server_requests_seconds_count{error="none",method="POST",outcome="CLIENT_ERROR",status="409",uri="/api/v1/orders"} 5.0
http_server_requests_seconds_sum{error="none",method="POST",outcome="CLIENT_ERROR",status="409",uri="/api/v1/orders"} 0.5
http_server_requests_seconds_count{error="none",method="GET",outcome="SUCCESS",status="200",uri="/actuator/health"} 20.0
http_server_requests_seconds_sum{error="none",method="GET",outcome="SUCCESS",status="200",uri="/actuator/health"} 20.0
hikaricp_connections_acquire_seconds_count{pool="HikariPool-1"} 30.0
hikaricp_connections_acquire_seconds_sum{pool="HikariPool-1"} 2.1
hikaricp_connections_max{pool="HikariPool-1"} 10.0
hikaricp_connections_active{pool="HikariPool-1"} 0.0
order_creation_results_total{result="created"} 12.0
order_creation_results_total{result="sale_sold_out"} 5.0
order_creation_results_total{result="idempotency_conflict"} 0.0
jvm_gc_pause_seconds_count{action="end of minor GC",cause="G1 Evacuation Pause"} 4.0
jvm_gc_pause_seconds_sum{action="end of minor GC",cause="G1 Evacuation Pause"} 0.07
process_cpu_time_ns_total 13.5E9
'''


def scrape(*lines):
    return parse_prometheus('\n'.join(lines))


class PrometheusParseTest(unittest.TestCase):
    def test_parses_labels_values_and_skips_comments(self):
        samples = parse_prometheus('# TYPE x gauge\nx{a="1",b="q\\"z"} 2.5 1700000000\ny 3\nz NaN\nbroken line here\n')
        self.assertEqual(samples[('x', (('a', '1'), ('b', 'q"z')))], 2.5)
        self.assertEqual(samples[('y', ())], 3.0)
        self.assertNotEqual(samples[('z', ())], samples[('z', ())])
        self.assertEqual(len(samples), 3)

    def test_prefix_filter_keeps_only_wanted_series(self):
        samples = parse_prometheus('hikaricp_connections_active{pool="p"} 3\nother_metric 1\n', ('hikaricp',))
        self.assertEqual(list(samples), [('hikaricp_connections_active', (('pool', 'p'),))])

    def test_total_sums_matching_labels_and_ignores_nan(self):
        samples = scrape('m{a="1",b="x"} 2', 'm{a="1",b="y"} 3', 'm{a="2",b="x"} 100', 'm{a="1",b="z"} NaN')
        self.assertEqual(total(samples, 'm', a='1'), 5)
        self.assertEqual(total(samples, 'm', a='1', b='y'), 3)
        self.assertEqual(total(samples, 'absent'), 0)

    def test_delta_is_after_minus_before(self):
        self.assertEqual(delta(scrape('c_total 4'), scrape('c_total 9'), 'c_total'), 5)

    def test_peak_ignores_scrapes_without_the_series(self):
        samples = [scrape('g 2'), scrape('other 1'), scrape('g 7'), scrape('g 3')]
        self.assertEqual(peak(samples, 'g'), 7)
        self.assertIsNone(peak(samples, 'never'))
        self.assertIsNone(peak([], 'g'))

    def test_mean_over_scrapes_that_have_the_series(self):
        samples = [scrape('g 2'), scrape('other 1'), scrape('g 4')]
        self.assertEqual(mean(samples, 'g'), 3.0)
        self.assertIsNone(mean(samples, 'never'))


class ManagementSummaryTest(unittest.TestCase):
    def setUp(self):
        self.samples = [scrape('hikaricp_connections_active{pool="p"} 4', 'hikaricp_connections_pending{pool="p"} 0',
                               'process_cpu_usage 0.2'),
                        scrape('hikaricp_connections_active{pool="p"} 10', 'hikaricp_connections_pending{pool="p"} 25',
                               'process_cpu_usage 0.6', 'hikaricp_connections_acquire_seconds_max{pool="p"} 0.8')]
        self.summary = management_summary(parse_prometheus(BEFORE), parse_prometheus(AFTER), self.samples)

    def test_http_deltas_exclude_the_actuator_and_unchanged_series(self):
        self.assertEqual(self.summary['http_server'], [
            {'method': 'POST', 'uri': '/api/v1/orders', 'status': '201', 'count': 10, 'mean_ms': 200.0},
            {'method': 'POST', 'uri': '/api/v1/orders', 'status': '409', 'count': 5, 'mean_ms': 100.0}])
        self.assertEqual(endpoint_requests(self.summary, 'POST', '/api/v1/orders'), 15)

    def test_hikari_acquire_delta_and_gauge_peaks(self):
        hikari = self.summary['hikari']
        self.assertEqual(hikari['acquire_count'], 20)
        self.assertEqual(hikari['acquire_mean_ms'], 100.0)
        self.assertEqual((hikari['active_peak'], hikari['pending_peak']), (10, 25))
        self.assertEqual(hikari['acquire_max_ms_peak'], 800.0)
        self.assertEqual(hikari['max_connections'], 10)

    def test_jvm_and_business_counters(self):
        self.assertEqual(self.summary['jvm']['gc_pause_count'], 3)
        self.assertEqual(self.summary['jvm']['gc_pause_seconds'], 0.06)
        self.assertEqual(self.summary['jvm']['process_cpu_usage_peak'], 0.6)
        self.assertEqual(self.summary['jvm']['process_cpu_usage_mean'], 0.4)
        self.assertEqual(self.summary['jvm']['cpu_seconds'], 10.5)
        self.assertIsNone(self.summary['jvm']['tomcat_threads_busy_peak'])
        self.assertEqual(self.summary['business']['order_creation'], {'created': 10, 'sale_sold_out': 5})

    def test_combine_adds_counts_weights_means_and_takes_peaks(self):
        other = management_summary(parse_prometheus(BEFORE), parse_prometheus(AFTER), [scrape(
            'hikaricp_connections_active{pool="p"} 12', 'hikaricp_connections_pending{pool="p"} 1')])
        combined = combine_management({'a': self.summary, 'b': other})
        self.assertEqual(combined['http_server'][0]['count'], 20)
        self.assertEqual(combined['http_server'][0]['mean_ms'], 200.0)
        self.assertEqual(combined['hikari']['acquire_count'], 40)
        self.assertEqual(combined['hikari']['active_peak'], 12)
        self.assertEqual(combined['hikari']['pending_peak'], 25)
        self.assertEqual(combined['jvm']['cpu_seconds'], 21.0)
        self.assertEqual(combined['business']['order_creation'], {'created': 20, 'sale_sold_out': 10})


def observe(waits, wait_ms, digests, deadlocks=0, current='0'):
    rows = '\n'.join('\t'.join(str(x) for x in row) for row in digests)
    return (f'mysql-observe snapshot\n\n== server ==\ntaken_at_utc\tversion\n2026-01-01T00:00:00Z\t8.4.7\n\n'
            f'== top 50 statement digests by total latency (times in ms) ==\n'
            f'schema_name\tcalls\ttotal_ms\tavg_ms\tmax_ms\tlock_ms\trows_examined\trows_sent\tno_index_used\terrors\tdigest_text\n'
            f'{rows}\n\n== current lock waits (none) ==\n\n== InnoDB row lock counters (cumulative) ==\n'
            f'counter\tvalue\nInnodb_row_lock_current_waits\t{current}\nInnodb_row_lock_time\t{wait_ms}\n'
            f'Innodb_row_lock_time_max\t900\nInnodb_row_lock_waits\t{waits}\n\n'
            f'== InnoDB deadlocks and lock timeouts (cumulative) ==\ncounter\tvalue\nlock_deadlocks\t{deadlocks}\n'
            f'lock_timeouts\t0\n')


class MysqlObserveTest(unittest.TestCase):
    def test_parse_splits_sections_into_typed_rows(self):
        parsed = parse_observe(observe(3, 120, [('limited_sale', 5, 10.5, 2.1, 4.0, 1.5, 5, 5, 0, 0, 'SELECT 1')]))
        digest = next(section for title, section in parsed.items() if title.startswith('top 50'))['rows'][0]
        self.assertEqual((digest['calls'], digest['total_ms'], digest['digest_text']), (5, 10.5, 'SELECT 1'))
        self.assertEqual(parsed['current lock waits (none)']['rows'], [])

    def test_delta_of_lock_counters_and_ranked_digests(self):
        before = parse_observe(observe(10, 100, [('s', 1, 1.0, 1.0, 1.0, 0.0, 1, 1, 0, 0, 'SELECT a'),
                                                 ('s', 4, 4.0, 1.0, 1.0, 0.0, 4, 4, 0, 0, 'COMMIT')]))
        after = parse_observe(observe(40, 700, [('s', 1, 1.0, 1.0, 1.0, 0.0, 1, 1, 0, 0, 'SELECT a'),
                                                ('s', 9, 504.0, 56.0, 300.0, 480.0, 9, 9, 0, 0, 'COMMIT'),
                                                ('s', 2, 8.0, 4.0, 5.0, 0.0, 2, 2, 0, 1, 'INSERT b')],
                                      deadlocks=2, current='3'))
        result = observe_delta(before, after, top=5)
        self.assertEqual((result['row_lock_waits'], result['row_lock_time_ms']), (30, 600))
        self.assertEqual(result['row_lock_wait_avg_ms'], 20.0)
        self.assertEqual((result['deadlocks'], result['lock_timeouts']), (2, 0))
        self.assertEqual(result['row_lock_time_max_ms_after'], 900)
        self.assertEqual(result['row_lock_current_waits_after'], 3)
        names = [row['digest_text'] for row in result['top_digests']]
        self.assertEqual(names, ['COMMIT', 'INSERT b'])
        self.assertEqual(result['top_digests'][0]['calls'], 5)
        self.assertEqual(result['top_digests'][0]['lock_ms'], 480.0)
        self.assertEqual(result['top_digests'][1]['errors'], 1)

    def test_top_limit_and_unavailable_sections(self):
        text = observe(0, 0, [('s', i + 1, i + 1.0, 1.0, 1.0, 0.0, 1, 1, 0, 0, f'Q{i}') for i in range(4)])
        broken = text.replace('counter\tvalue\nlock_deadlocks\t0\nlock_timeouts\t0\n',
                              '(section unavailable: check connection, password and privileges)\n')
        result = observe_delta(parse_observe(observe(0, 0, [])), parse_observe(broken), top=2)
        self.assertEqual([row['digest_text'] for row in result['top_digests']], ['Q3', 'Q2'])
        self.assertEqual(result['unavailable_sections'], ['InnoDB deadlocks and lock timeouts (cumulative)'])
        self.assertIsNone(result['row_lock_wait_avg_ms'])


if __name__ == '__main__':
    unittest.main()
