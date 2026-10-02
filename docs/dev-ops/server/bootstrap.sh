#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
python3 preflight.py
docker compose up -d --wait mysql vector_db redis
# The export is imported only into empty databases. Never overwrite an existing server.
tables=$(docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"ai-agent-station-study\""')
if [ "$tables" = "0" ]; then
  docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 ai-agent-station-study' < import/mysql.sql
fi
pg_tables=$(docker compose exec -T vector_db sh -c 'psql -U "$POSTGRES_USER" -d ai-rag-knowledge -Atc "SELECT count(*) FROM pg_tables WHERE schemaname=current_schema()"')
if [ "$pg_tables" = "0" ]; then
  docker compose exec -T vector_db sh -c 'psql -U "$POSTGRES_USER" -d ai-rag-knowledge --single-transaction -v ON_ERROR_STOP=1' < import/pgvector.sql
fi
role_column=$(docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=\"ai-agent-station-study\" AND table_name=\"admin_user\" AND column_name=\"role\""')
if [ "$role_column" != "1" ]; then
  echo 'V061 migration is missing from this export. Apply it before starting.' >&2
  exit 1
fi
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 ai-agent-station-study' < import/mcp-linux.sql
docker compose up -d --build
docker compose ps
echo 'Started. Check HTTPS login, admin restrictions, SSE, EvalOps and MCP connectivity before inviting users.'
