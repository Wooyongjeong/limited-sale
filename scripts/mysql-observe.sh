#!/bin/sh
# Prints a text snapshot of MySQL statement digests, lock waits and InnoDB row-lock counters.
# Save one before and one after an experiment and compare them with diff.
#
# Connection comes from options or the environment, and the password only from MYSQL_PWD
# (or MYSQL_ROOT_PASSWORD) so it never appears in argv, in this output or in the shell history.
# The account needs SELECT on performance_schema and the PROCESS privilege; root works.
#
#   scripts/mysql-observe.sh [--host H] [--port P] [--user U] [--schema DB] [--top N] [--container NAME]
#
# --container runs the mysql client inside that container through docker exec instead of the host client.
# Statement digest counters are cumulative since server start, so read the difference between two snapshots.
set -eu

HOST=${MYSQL_OBSERVE_HOST:-127.0.0.1}
PORT=${MYSQL_OBSERVE_PORT:-${MYSQL_PORT:-3306}}
DB_USER=${MYSQL_OBSERVE_USER:-root}
SCHEMA=${MYSQL_OBSERVE_SCHEMA:-}
TOP=${MYSQL_OBSERVE_TOP:-15}
CONTAINER=${MYSQL_OBSERVE_CONTAINER:-}

while [ $# -gt 0 ]; do
    case "$1" in
        --host) HOST=${2:?--host needs a value}; shift 2 ;;
        --port) PORT=${2:?--port needs a value}; shift 2 ;;
        --user) DB_USER=${2:?--user needs a value}; shift 2 ;;
        --schema) SCHEMA=${2:?--schema needs a value}; shift 2 ;;
        --top) TOP=${2:?--top needs a value}; shift 2 ;;
        --container) CONTAINER=${2:?--container needs a value}; shift 2 ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1 (the password is read from MYSQL_PWD, never from an option)" >&2; exit 2 ;;
    esac
done

case "$TOP" in ''|*[!0-9]*) echo '--top must be a positive integer' >&2; exit 2 ;; esac
case "$SCHEMA" in *[!a-zA-Z0-9_]*) echo '--schema may only contain letters, digits and underscores' >&2; exit 2 ;; esac
MYSQL_PWD=${MYSQL_PWD:-${MYSQL_ROOT_PASSWORD:-}}
export MYSQL_PWD

mysql_client() {
    if [ -n "$CONTAINER" ]; then
        docker exec -i -e MYSQL_PWD "$CONTAINER" mysql --batch -h127.0.0.1 -u"$DB_USER"
    else
        mysql --batch -h"$HOST" -P"$PORT" -u"$DB_USER" --protocol=TCP
    fi
}

FAILED=0
section() {
    printf '\n== %s ==\n' "$1"
    if ! printf '%s\n' "$2" | mysql_client 2>&1; then
        echo "(section unavailable: check connection, password and privileges)"
        FAILED=1
    fi
}

SCHEMA_FILTER="SCHEMA_NAME IS NULL OR SCHEMA_NAME NOT IN ('mysql','sys','performance_schema','information_schema')"
if [ -n "$SCHEMA" ]; then
    SCHEMA_FILTER="SCHEMA_NAME = '$SCHEMA'"
fi

echo "mysql-observe snapshot"
section "server" "SELECT DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%dT%H:%i:%sZ') AS taken_at_utc, VERSION() AS version,
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Uptime') AS uptime_seconds,
  @@global.performance_schema AS performance_schema_on;"

section "top $TOP statement digests by total latency (times in ms; this query's own digests are excluded)" \
"SELECT COALESCE(SCHEMA_NAME, '') AS schema_name, COUNT_STAR AS calls,
  ROUND(SUM_TIMER_WAIT / 1000000000, 3) AS total_ms, ROUND(AVG_TIMER_WAIT / 1000000000, 3) AS avg_ms,
  ROUND(MAX_TIMER_WAIT / 1000000000, 3) AS max_ms, ROUND(SUM_LOCK_TIME / 1000000000, 3) AS lock_ms,
  SUM_ROWS_EXAMINED AS rows_examined, SUM_ROWS_SENT AS rows_sent, SUM_NO_INDEX_USED AS no_index_used,
  SUM_ERRORS AS errors, LEFT(REPLACE(DIGEST_TEXT, CHAR(10), ' '), 160) AS digest_text
FROM performance_schema.events_statements_summary_by_digest
WHERE ($SCHEMA_FILTER) AND DIGEST_TEXT NOT LIKE '%performance_schema%' AND DIGEST_TEXT NOT LIKE '%information_schema%'
  AND DIGEST_TEXT NOT LIKE 'SHOW %'
ORDER BY SUM_TIMER_WAIT DESC LIMIT $TOP;"

section "current lock waits (performance_schema.data_lock_waits; none means nobody is waiting now)" \
"SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS waiting_trx, w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_trx,
  r.OBJECT_SCHEMA AS object_schema, r.OBJECT_NAME AS object_name, COALESCE(r.INDEX_NAME, '') AS index_name,
  r.LOCK_TYPE AS lock_type, r.LOCK_MODE AS waiting_mode, b.LOCK_MODE AS blocking_mode,
  COALESCE(r.LOCK_DATA, '') AS waiting_lock_data
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID
ORDER BY w.REQUESTING_ENGINE_TRANSACTION_ID;"

section "InnoDB row lock counters (cumulative; time in ms)" \
"SELECT VARIABLE_NAME AS counter, VARIABLE_VALUE AS value FROM performance_schema.global_status
WHERE VARIABLE_NAME LIKE 'Innodb_row_lock%' ORDER BY VARIABLE_NAME;"

section "InnoDB deadlocks and lock timeouts (cumulative)" \
"SELECT NAME AS counter, \`COUNT\` AS value FROM information_schema.INNODB_METRICS
WHERE NAME IN ('lock_deadlocks', 'lock_timeouts') ORDER BY NAME;"

exit "$FAILED"
