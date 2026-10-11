# Runs the DAO's actual SQL against MySQL using connection-local synthetic tables (no migration).
# Business tables are only read for optional existing-session evidence, never modified.
param(
    [string]$Container = 'mysql',
    [string]$Database = 'ai-agent-station-study',
    [string]$ExistingSessionId = '',
    [string]$ExistingUserId = '10001'
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$mapperPath = Join-Path $repoRoot 'ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/IConversationOwnerDao.java'
$mapper = Get-Content -LiteralPath $mapperPath -Raw -Encoding UTF8
$match = [regex]::Match($mapper, '(?s)@Select\("""\s*(.*?)\s*"""\)\s*String persistedOwner')
if (-not $match.Success) { throw 'Cannot locate persistedOwner SQL in the mapper.' }
$query = $match.Groups[1].Value

function SqlLiteral([string]$value) {
    return "'" + $value.Replace('\', '\\').Replace("'", "''") + "'"
}

function OwnerQuery([string]$sessionId, [string]$userId = '10001') {
    return $query.Replace('#{sessionId}', (SqlLiteral $sessionId)).
        Replace('#{conversationId}', (SqlLiteral "default:${userId}:$sessionId")).
        Replace('#{legacyConversationId}', (SqlLiteral "${userId}:$sessionId"))
}

$statements = [System.Collections.Generic.List[string]]::new()
$statements.Add(@'
SELECT VERSION() AS mysql_version;
SELECT TABLE_NAME,COLUMN_NAME,COLLATION_NAME
FROM information_schema.columns
WHERE table_schema=DATABASE()
  AND table_name IN ('ai_chat_memory','ai_run_history') AND column_name='user_id'
ORDER BY table_name;
'@)
if ($ExistingSessionId) {
    $existingQuery = OwnerQuery $ExistingSessionId $ExistingUserId
    $statements.Add("SELECT $(SqlLiteral $ExistingSessionId) AS existing_session, ($existingQuery) AS persisted_owner;")
}
$statements.Add(@'
CREATE TEMPORARY TABLE ai_chat_memory (
  conversation_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
);
CREATE TEMPORARY TABLE ai_run_history (
  session_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
);
INSERT INTO ai_chat_memory VALUES
 ('same','10001'),('default:10001:same','10001'),('10001:same','10001'),
 ('memory_only','10001'),('different','10001'),('case','CaseOwner'),
 ('trailing','10001'),('mixed_null','10001'),('mixed_empty','10001'),
 ('memory_case','CaseOwner'),('memory_case','caseOwner');
INSERT INTO ai_run_history VALUES
 ('same','10001'),('same','10001'),('history_only','10001'),
 ('different','other'),('case','caseOwner'),('trailing','10001 '),
 ('mixed_null',NULL),('mixed_empty',''),('only_null',NULL),('only_empty',''),
 ('history_trailing','10001'),('history_trailing','10001 ');
'@)

$cases = [ordered]@{
    same = '10001'
    memory_only = '10001'
    history_only = '10001'
    different = '__legacy_unowned__'
    case = '__legacy_unowned__'
    trailing = '__legacy_unowned__'
    mixed_null = '__legacy_unowned__'
    mixed_empty = '__legacy_unowned__'
    only_null = '__legacy_unowned__'
    only_empty = '__legacy_unowned__'
    memory_case = '__legacy_unowned__'
    history_trailing = '__legacy_unowned__'
    missing = $null
}
foreach ($entry in $cases.GetEnumerator()) {
    $expected = if ($null -eq $entry.Value) { 'NULL' } else { SqlLiteral $entry.Value }
    $fixtureQuery = OwnerQuery $entry.Key
    $statements.Add(@"
SELECT $(SqlLiteral $entry.Key) AS fixture, resolved_owner, $expected AS expected_owner,
  IF(CAST(resolved_owner AS BINARY) <=> CAST($expected AS BINARY), 'PASS', 'FAIL') AS result
FROM ($fixtureQuery) AS fixture_result(resolved_owner);
"@)
}
$statements.Add('DROP TEMPORARY TABLE ai_chat_memory, ai_run_history;')

$originalOutputEncoding = $OutputEncoding
try {
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $output = ($statements -join "`n") | docker exec -i $Container sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --database="$1"' sh $Database
    $mysqlExitCode = $LASTEXITCODE
} finally {
    $OutputEncoding = $originalOutputEncoding
}
$output | Write-Output
if ($mysqlExitCode -ne 0) { throw "MySQL fixture execution failed with exit code $mysqlExitCode." }
$passed = @($output | Where-Object { $_ -match "`tPASS$" }).Count
if ($passed -ne $cases.Count) { throw "Expected $($cases.Count) passing fixtures; got $passed." }
Write-Output "All $passed MySQL owner fixtures passed; temporary tables removed."
