"""Generate a Linux-only SQL patch from exported MCP rows. Never prints credentials.

Input: import/mcp-source.json (id, name, type, config), output: import/mcp-linux.sql.
This does not modify the developer database. Apply only to the imported server DB.
"""
import json
import re
import shlex
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

ROOT = Path(__file__).resolve().parent
PORTS = {8101: 'mcp-server-csdn-app', 8102: 'mcp-server-weixin-app',
         8000: 'grafana-mcp', 9000: 'mcp-server-hmdp', 3001: 'markitdown',
         9200: 'elasticsearch', 4000: 'grafana:3000'}


def convert(row):
    warnings = []
    raw = row['config'] or '{}'
    try:
        cfg = json.loads(raw)
    except json.JSONDecodeError:
        # Recover a known legacy SQL-escaping bug: raw OAuth JSON was embedded in a JSON string.
        needle = '"GOOGLE_OAUTH_CREDENTIALS":"'
        start = raw.find(needle)
        if row['id'] != '9001' or start < 0: raise
        start += len(needle)
        credentials, length = json.JSONDecoder().raw_decode(raw[start:])
        end = start + length
        if raw[end:end+1] != '"': raise ValueError('Invalid legacy OAuth credentials')
        cfg = json.loads(raw[:start-1] + json.dumps(json.dumps(credentials)) + raw[end+1:])
        warnings.append('Recovered malformed embedded OAuth JSON in the server export only.')
    def value(v):
        if not isinstance(v, str): return v
        normalized = v.replace('\\', '/')
        for marker in ('/mcp-servers/', '/docs/dev-ops/mcp-wrappers/'):
            if marker in normalized and re.match(r'^[A-Za-z]:/', normalized):
                return '/app' + normalized[normalized.index(marker):]
        if v.startswith(('http://', 'https://')):
            url = urlsplit(v)
            if url.hostname in ('127.0.0.1', 'localhost', 'host.docker.internal'):
                target = PORTS.get(url.port)
                if target:
                    host = target if ':' in target else f'{target}:{url.port}'
                    return urlunsplit((url.scheme, host, url.path, url.query, url.fragment))
                warnings.append('Unmapped loopback endpoint; configure its server address.')
        if re.match(r'^[A-Za-z]:/', normalized): warnings.append('Unmapped Windows path; mount or relocate it.')
        return v
    if row['type'] == 'stdio':
        for server in cfg.values():
            if not isinstance(server, dict): continue
            args = server.get('args', [])
            if server.get('command', '').lower() in ('cmd', 'cmd.exe'):
                while args and args[0].lower() in ('/d', '/s'): args = args[1:]
                if len(args) < 2 or args[0].lower() != '/c':
                    raise ValueError(f"Unsupported command wrapper for MCP {row['id']}")
                argv = args[1:]
                if len(argv) == 1:
                    command_line = re.sub(r'^\s*chcp\s+\d+(?:\s*>\s*nul)?\s*&&\s*', '', argv[0], flags=re.I)
                    argv = shlex.split(command_line)
                if not argv or any(t in ('&&','||',';','|','>','<') for t in argv):
                    raise ValueError('Shell expression requires manual migration')
                server['command'], args = argv[0], argv[1:]
            server['args'] = [value(arg) for arg in args]
            if row['id'] == '5003':
                # The filesystem MCP gets an empty dedicated workspace, not developer home/source/secrets.
                server['args'] = [a for a in server['args'] if not re.match(r'^[A-Za-z]:[/\\]', str(a))]
                server['args'].append('/app/data/workspace')
                warnings = [w for w in warnings if not w.startswith('Unmapped Windows')]
            if server.get('command') == 'npx' and '-y' not in server['args']:
                server['args'].insert(0, '-y')
            env = server.get('env', {})
            if row['id'] == '9001' and str(env.get('GOOGLE_OAUTH_CREDENTIALS','')).startswith('{'):
                credentials = json.loads(env['GOOGLE_OAUTH_CREDENTIALS'])
                folder = ROOT/'import/calendar'; folder.mkdir(parents=True,exist_ok=True)
                (folder/'gcp-oauth.keys.json').write_text(json.dumps(credentials),encoding='utf-8')
                env['GOOGLE_OAUTH_CREDENTIALS'] = '/app/calendar/gcp-oauth.keys.json'
                env['GOOGLE_CALENDAR_MCP_TOKEN_PATH'] = '/app/calendar/tokens.json'
                warnings.append('Calendar OAuth authorization tokens must also be migrated or renewed.')
            for key in list(env):
                if key.upper() in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY'):
                    if urlsplit(str(env[key])).hostname in ('127.0.0.1', 'localhost'):
                        del env[key]
                        warnings.append('Removed desktop proxy; verify provider connectivity from server.')
                else: env[key] = value(env[key])
    else:
        cfg = {key: value(v) for key, v in cfg.items()}
    if row['id'] == '5001' and cfg.get('baseUri') == 'http://updated.example.com':
        cfg['baseUri'] = 'http://mcp-server-csdn-app:8101'
        warnings.append('Replaced the existing placeholder CSDN endpoint with the deployed service.')
    encoded = json.dumps(cfg, ensure_ascii=False, separators=(',', ':'))
    if len(encoded) > 1024:
        raise ValueError(f"MCP {row['id']} exceeds current transport_config column length")
    return encoded, sorted(set(warnings))


def main():
    rows = json.loads((ROOT / 'import/mcp-source.json').read_text(encoding='utf-8'))
    statements = ['-- Contains credentials. Do not commit or print this file.', 'START TRANSACTION;']
    report = []
    for row in rows:
        try:
            config, warnings = convert(row)
        except (ValueError, TypeError) as error:
            report.append({'id': row['id'], 'name': row['name'], 'blocked': True,
                           'notes': ['Source transport configuration is invalid; repair before enabling on the server.']})
            continue
        # Hex literals avoid SQL-mode-dependent quoting and backslash escaping.
        statements.append("UPDATE ai_client_tool_mcp SET transport_config=CONVERT(0x" +
                          config.encode().hex() + " USING utf8mb4) WHERE mcp_id=CONVERT(0x" +
                          row['id'].encode().hex() + " USING utf8mb4);")
        report.append({'id': row['id'], 'name': row['name'], 'notes': warnings})
    statements.append('COMMIT;')
    (ROOT / 'import/mcp-linux.sql').write_text('\n'.join(statements)+'\n', encoding='utf-8')
    (ROOT / 'import/mcp-migration-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Prepared {sum(not r.get("blocked") for r in report)} MCP configurations; review import/mcp-migration-report.json before server import.')
    if any(r.get('blocked') for r in report):
        print('Some source configurations are invalid. Preflight will block deployment until they are repaired.')

if __name__ == '__main__': main()
