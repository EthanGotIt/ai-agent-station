#!/bin/sh
set -eu

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 <backup.sql>" >&2
    exit 2
fi

if [ "${CONFIRM_MYSQL_RESTORE:-}" != "RESTORE_AI_AGENT_STATION" ]; then
    echo "Set CONFIRM_MYSQL_RESTORE=RESTORE_AI_AGENT_STATION to restore a backup." >&2
    exit 2
fi

docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot AI_AGENT_STATION' \
  < "$1"

echo "MySQL restore completed from: $1"
