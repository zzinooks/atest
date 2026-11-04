#!/usr/bin/env bash
# 매일 DB 백업 (cron: 40 3 * * * /opt/fgc/deploy/backup.sh). 14일 보관, 외부 저장소로 복사 권장.
set -euo pipefail
cd "$(dirname "$0")"
OUT=backups/fgc-$(date -u +%Y%m%dT%H%M%SZ).sql.gz
mkdir -p backups
docker compose exec -T db pg_dump -U fgc --no-owner fgc | gzip > "$OUT"
# 백업이 비어 있으면 실패 처리 (조용한 실패 방지)
[ "$(gzip -cd "$OUT" | grep -c 'CREATE TABLE')" -ge 10 ] || { echo "backup looks empty: $OUT" >&2; exit 1; }
find backups -name 'fgc-*.sql.gz' -mtime +14 -delete
echo "backup ok: $OUT"
