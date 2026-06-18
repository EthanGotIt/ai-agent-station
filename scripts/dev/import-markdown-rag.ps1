param(
    [string]$JavaHome
)

. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Get-RepoRoot
Set-LocalAppEnvironment -JavaHome $JavaHome

if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    throw '未检测到 OPENAI_API_KEY，应用启动仍需要真实对话模型密钥。'
}

Push-Location $repoRoot
try {
    Reset-PgVectorSchema

    & mvn -q -pl ai-agent-station-app -am '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=MarkdownRagImportSmokeTest' '-DRUN_REAL_AI_TESTS=true' '-DRUN_DB_MUTATION_TESTS=true' test
    if ($LASTEXITCODE -ne 0) {
        throw 'Markdown Parent-Child 导入失败。'
    }

    $reportFile = Join-Path $repoRoot 'ai-agent-station-app\target\surefire-reports\TEST-cn.ethan.ai.test.spring.ai.MarkdownRagImportSmokeTest.xml'
    if (-not (Test-Path $reportFile)) {
        throw '未找到 Markdown 导入 smoke 的 surefire 报告，无法确认测试是否真正执行。'
    }

    [xml]$report = Get-Content $reportFile
    $testCount = [int]$report.testsuite.tests
    if ($testCount -le 0) {
        throw 'Markdown 导入 smoke 未执行到任何测试用例，请检查 surefire 配置或测试过滤条件。'
    }

    Write-Host 'Markdown Parent-Child 导入完成。'
} finally {
    Pop-Location
}
