param(
    [string]$Python = 'python',
    [string]$Node = 'node',
    [string]$Maven = 'mvn',
    [string]$OutputDirectory = '.codex-v2-validation/final'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repo
try {
    if ($env:TAGENT_TEST_DB_URL -notmatch '^jdbc:mysql://(127\.0\.0\.1|localhost)(:[0-9]+)?/tagent_workspace_isolation_test(\?.*)?$') {
        throw 'Set TAGENT_TEST_DB_URL to the explicitly isolated loopback schema tagent_workspace_isolation_test.'
    }
    if ($env:TAGENT_TEST_REDIS_PORT -notmatch '^\d+$') {
        throw 'Set TAGENT_TEST_REDIS_PORT to the dedicated loopback test Redis port.'
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $output = (Resolve-Path $OutputDirectory).Path
    $tests = @(
        'RunHistoryTest','RunHistorySessionAccessTest','PersistedConversationAccessTest','SiteAuthenticationTest','AccountPasswordMySqlIntegrationTest',
        'EvalJudgeModelAccessTest','EvalJudgeModelEndpointTest','EvalJudgeRequestUrlTest','EvalJudgeResponseSupportTest',
        'EvalJudgeResponseRetryTest','EvalJudgeRetryTest','EvalJudgeRetryConcurrencyTest',
        'ModelStreamAccountingTest','ModelUsageAccountingTest','ModelUsageMySqlIntegrationTest',
        'PersonalModelConnectionTest','ModelApiSecretRedactionTest','ModelRequestGuardTest','RunBudgetTest','RunBudgetTerminalAccountingTest',
        'ReasoningCallIsolationTest','WorkspaceOutcomeTest','RunEventPublisherTest','McpConnectionLifecycleTest','McpConnectionCapacityBaselineTest',
        'McpCatalogRecoveryTest','ResolvedToolLeaseTest','ToolVectorStoreRelevanceTest','DynamicToolRelevanceTest','PublicToolDiscoveryTest',
        'WorkspaceRunArtifactTest','AutomaticArtifactManagerTest','ArtifactStreamingProgressTest','ArtifactToolStreamRecoveryTest','RunNotificationProjectorTest','NotificationServiceTest','NotificationInboxAccessTest','RedisNotificationStoreTest',
        'McpToolMetadataTest','McpToolNamingTest','McpObserveReadPathTest','MeteredToolCallbackDelegateRefreshTest','MeteredToolRetryBudgetTest',
        'ModelStreamBudgetSettlementTest','FlowBoundMcpCapabilitiesTest','WorkspaceNodeRuntimeTest','AutoAnalyzerToolVisibilityTest',
        'FlowPlanningCatalogOnlyTest','RequestToolAccountingScopeTest','AdvisorRunBudgetPropagationTest','AmapPoiResultCompactorTest','StreamingProgressEmitterTest',
        'EvalLifecycleServiceTest','EvalTraceCodecTest','SemanticCacheIsolationTest','LongTermMemoryControllerTest','RagPublicationGuardTest',
        'RagIngestionLifecycleTest','RagStorageContractTest','KnowledgeAccessTest','RagBeanWiringTest',
        'FixedStreamingFailureTest','StreamingFailurePropagationTest','ModelStreamErrorTest','AutoNodeStep3EnforceContractTest','FlowDagFailurePropagationContractTest','FlowVersionResumeTest',
        'AgentDispatchIdempotencyTest','AgentPreparationDispatchTest','RunPreparationProgressTest','AiAgentControllerRunReconnectTest','FlowPlanLifecycleTest','RunCleanupFailureTest','MeteredToolCallbackApprovalTest','MeteredToolCallbackContextSanitizationTest',
        'MeteredToolCallbackFirstAttemptMetricsTest','MeteredToolCallbackProgressTest','RobustToolCallingManagerGateTest',
        'RobustToolCallingManagerParallelTest','RobustToolOriginalNameTest','StreamingActivityTrackerTest','StreamingAttemptLifecycleTest','StreamingRetryPolicyTest',
        'ConversationTurnMemoryPersistenceTest','SummarizingChatMemoryFloorTest','WorkspaceVersionTest',
        'RedisToolEvidenceAccessTest','RedisToolEvidenceIntegrationTest','RedisLifecycleContractTest','AmapRouteResultCompactorTest',
        'MeteredToolDebugGuardTest','ToolResultReferenceManagerTest','ToolResultReferenceServiceTest','WorkspaceNodeDebugTest',
        'RagDirectoryControllerTest','RagFileDeletionTest','HybridRetrievalReliabilityTest','RagFusionCancellationTest',
        'RagAnswerAdvisorIsolationTest','RagUploadRetryTest','WorkspaceServiceIntegrationTest','RagEvidenceEmitterTest',
        'WorkspaceRuntimeIsolationTest','WorkspaceMcpPolicyTest','WorkspaceExecutionGuardTest','SharedUserInputContextTest'
    )
    Write-Host 'Running curated Java regressions, including isolated MySQL and Redis checks...'
    & $Maven -o -pl ai-agent-station-study-app -am "-Dtest=$($tests -join ',')" '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' clean test *> (Join-Path $output 'java.log')
    if ($LASTEXITCODE -ne 0) { throw "Java checks failed; see $output/java.log" }
    $count=0; $skipped=0; $failures=0; $errors=0
    foreach ($file in Get-ChildItem 'ai-agent-station-study-app/target/surefire-reports/TEST-*.xml') {
        [xml]$report = Get-Content -Raw -LiteralPath $file.FullName
        $count += [int]$report.testsuite.tests; $skipped += [int]$report.testsuite.skipped
        $failures += [int]$report.testsuite.failures; $errors += [int]$report.testsuite.errors
    }
    if ($count -eq 0 -or $skipped -ne 0 -or $failures -ne 0 -or $errors -ne 0) { throw "Incomplete Java verification: total=$count skipped=$skipped failures=$failures errors=$errors" }
    Copy-Item -LiteralPath 'ai-agent-station-study-app/target/mcp-capacity-baseline.json' -Destination $output
    Write-Host 'Packaging local application (tests already verified above)...'
    & $Maven -o -pl ai-agent-station-study-app -am '-DskipTests=true' '-Dmaven.test.skip=true' "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' package *> (Join-Path $output 'package.log')
    if ($LASTEXITCODE -ne 0) { throw "Local packaging failed; see $output/package.log" }
    Write-Host 'Running run-event ledger regression checks...'
    & $Node --test 'ai-agent-station-study-app/src/test/frontend/run-event-ledger.test.cjs' *> (Join-Path $output 'run-event-ledger.log')
    if ($LASTEXITCODE -ne 0) { throw "Run-event ledger checks failed; see $output/run-event-ledger.log" }
    Write-Host 'Running Eval trace display and export regression checks...'
    & $Node --test 'ai-agent-station-study-app/src/test/frontend/eval-trace-ui.test.cjs' *> (Join-Path $output 'eval-trace-ui.log')
    if ($LASTEXITCODE -ne 0) { throw "Eval trace UI checks failed; see $output/eval-trace-ui.log" }
    Write-Host 'Running Eval Judge model selection and failed-question retry regression checks...'
    & $Node --test 'ai-agent-station-study-app/src/test/frontend/eval-judge-models.test.cjs' 'ai-agent-station-study-app/src/test/frontend/eval-judge-retry.test.cjs' *> (Join-Path $output 'eval-judge-ui.log')
    if ($LASTEXITCODE -ne 0) { throw "Eval Judge UI checks failed; see $output/eval-judge-ui.log" }
    $frontends = @('history-account','knowledge-ui','models-ui','observe-names','outcomes-ui','run-replay','workspace-ui','workspace-nav','run-artifacts','inbox-completion','eval-session-history')
    foreach ($suite in $frontends) {
        Write-Host "Running local Chromium checks: $suite"
        & $Node "ai-agent-station-study-app/src/test/frontend/$suite.integration.cjs" *> (Join-Path $output "$suite.log")
        if ($LASTEXITCODE -ne 0) { throw "Browser checks failed: $suite; see $output" }
    }
    Write-Host 'Running offline operational checks (restore drill requires a separate, explicit command)...'
    & $Python -m unittest discover -s docs/dev-ops/server -p test_v2_ops.py -v *> (Join-Path $output 'operations.log')
    if ($LASTEXITCODE -ne 0) { throw "Operational checks failed; see $output/operations.log" }
    $result = [ordered]@{ timestamp=(Get-Date).ToString('o'); javaTests=$count; javaSkipped=$skipped; browserSuites=$frontends; operations='offline PASS; isolated restore verified separately'; scope='local-only; synthetic provider responses; no production traffic' }
    $result | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $output 'summary.json')
    Write-Host "PASS: $count Java tests, $($frontends.Count) browser suites, run-event ledger, Eval trace/Judge UI and offline operational checks. Evidence: $output"
} finally { Pop-Location }
