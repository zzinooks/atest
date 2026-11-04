#!/usr/bin/env bash
# 복구: ./restore.sh backups/fgc-....sql.gz  (기존 DB 를 덮어쓴다 — 확인 후 실행)
set -euo pipefail
cd "$(dirname "$0")"
[ -f "${1:?backup file}" ]
read -r -p "DB fgc 를 $1 로 덮어씁니다. 계속? (yes) " ok; [ "$ok" = yes ]
docker compose stop backend
docker compose exec -T db psql -U fgc -d postgres -c "DROP DATABASE fgc WITH (FORCE);" -c "CREATE DATABASE fgc OWNER fgc;"
gzip -cd "$1" | docker compose exec -T db psql -U fgc -d fgc -v ON_ERROR_STOP=1 >/dev/null
docker compose start backend
echo "restored from $1"
