#!/bin/sh
set -eu

backup_dir=${1:-deployment/backups}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_file="$backup_dir/ai-agent-station-$timestamp.sql"

mkdir -p "$backup_dir"
docker compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events AI_AGENT_STATION' \
  > "$backup_file"

echo "MySQL backup created: $backup_file"
