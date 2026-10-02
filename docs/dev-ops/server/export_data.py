"""Export local Docker databases without displaying credentials. Run before deployment.
Usage: python export_data.py --mysql mysql --postgres vector_db
Exports private data under ignored import/. Requires Docker; no Python packages needed.
"""
import argparse
import json
import subprocess
from pathlib import Path

def main():
    args = argparse.ArgumentParser()
    args.add_argument('--mysql', default='mysql')
    args.add_argument('--postgres', default='vector_db')
    opts = args.parse_args()
    root = Path(__file__).resolve().parent
    dest = root / 'import'; dest.mkdir(exist_ok=True)
    for name in ('mysql.sql', 'pgvector.sql'):
        if (dest/name).exists(): raise SystemExit(f'{name} already exists; archive it before exporting again.')
    jobs = [
        ('mysql.sql', opts.mysql, 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --set-gtid-purged=OFF --default-character-set=utf8mb4 ai-agent-station-study'),
        ('pgvector.sql', opts.postgres, 'pg_dump -U "$POSTGRES_USER" -d ai-rag-knowledge --no-owner --no-acl'),
    ]
    for name, container, command in jobs:
        temp = dest/(name+'.partial')
        with temp.open('wb') as output:
            subprocess.run(['docker', 'exec', container, 'sh', '-c', command], stdout=output, check=True)
        temp.rename(dest/name)
        print(f'Exported {name}')
    query = "SELECT JSON_OBJECT('id',mcp_id,'name',mcp_name,'type',transport_type,'config',transport_config) FROM ai_client_tool_mcp"
    command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 -N -B --raw ai-agent-station-study -e "'+query+'"'
    raw = subprocess.check_output(['docker','exec',opts.mysql,'sh','-c',command]).decode('utf-8')
    (dest/'mcp-source.json').write_text(json.dumps([json.loads(s) for s in raw.splitlines()],ensure_ascii=False),encoding='utf-8')
    import prepare_mcp
    prepare_mcp.main()
    print('Private exports completed. Transfer over SSH; do not commit import/.')

if __name__ == '__main__': main()
