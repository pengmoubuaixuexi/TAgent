"""Check deployment inputs without printing secrets. Python standard library only."""
from pathlib import Path
import subprocess
import sys
import json

root = Path(__file__).resolve().parent
errors = []
env = root / '.env'
values = {}
if env.exists():
    values = dict(line.split('=',1) for line in env.read_text(encoding='utf-8').splitlines()
                  if '=' in line and not line.lstrip().startswith('#'))
for key in ('DOMAIN','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','POSTGRES_PASSWORD','GRAFANA_ADMIN_PASSWORD',
            'PGADMIN_PASSWORD','REDIS_ADMIN_PASSWORD','LLM_BASE_URL','LLM_API_KEY','EMBEDDING_BASE_URL','EMBEDDING_API_KEY'):
    v = values.get(key,'').strip()
    if not v or 'REPLACE_' in v or '.example' in v: errors.append(f'Configure {key} in .env')
for name in ('certs/fullchain.pem','certs/privkey.pem','import/mysql.sql','import/pgvector.sql','import/mcp-linux.sql'):
    if not (root/name).is_file(): errors.append(f'Missing {name}')
if not (root.parents[2]/'ai-agent-station-study-app/target/ai-agent-station-study-app.jar').is_file():
    errors.append('Build the application JAR first')
if values.get('SESSION_COOKIE_SECURE','true').lower() != 'true': errors.append('Public HTTPS deployment requires SESSION_COOKIE_SECURE=true')
report = root / 'import/mcp-migration-report.json'
if report.is_file():
    for item in json.loads(report.read_text(encoding='utf-8')):
        if item.get('blocked'): errors.append('Repair MCP configuration ' + item['id'])
if errors:
    print('\n'.join(errors)); sys.exit(1)
subprocess.run(['docker','compose','--env-file','.env','-f','compose.yml','config','--quiet'],cwd=root,check=True)
print('Deployment inputs and Compose structure passed. This is not a capacity or connectivity test.')
