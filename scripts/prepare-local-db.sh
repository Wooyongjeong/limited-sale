#!/bin/sh
set -eu
: "${MYSQL_DATABASE:?}" "${MYSQL_USER:?}" "${MYSQL_PASSWORD:?}" "${MOCK_PG_DATABASE:?}" "${MOCK_PG_USER:?}" "${MOCK_PG_PASSWORD:?}"
# Restrict interpolated identifiers and local credentials to safe SQL characters.
for value in "$MYSQL_DATABASE" "$MYSQL_USER" "$MYSQL_PASSWORD" "$MOCK_PG_DATABASE" "$MOCK_PG_USER" "$MOCK_PG_PASSWORD"; do
    case "$value" in *[!a-zA-Z0-9_]*|'') echo 'Use only letters, digits and underscores in local database names and credentials.' >&2; exit 1;; esac
done
if [ "$MYSQL_DATABASE" = "$MOCK_PG_DATABASE" ] || [ "$MYSQL_USER" = "$MOCK_PG_USER" ]; then
    echo 'The two databases and users must be distinct.' >&2; exit 1
fi
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root' <<SQL
CREATE DATABASE IF NOT EXISTS \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS \`$MOCK_PG_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$MYSQL_USER'@'%' IDENTIFIED BY '$MYSQL_PASSWORD';
CREATE USER IF NOT EXISTS '$MOCK_PG_USER'@'%' IDENTIFIED BY '$MOCK_PG_PASSWORD';
GRANT ALL PRIVILEGES ON \`$MYSQL_DATABASE\`.* TO '$MYSQL_USER'@'%';
GRANT ALL PRIVILEGES ON \`$MOCK_PG_DATABASE\`.* TO '$MOCK_PG_USER'@'%';
SQL
