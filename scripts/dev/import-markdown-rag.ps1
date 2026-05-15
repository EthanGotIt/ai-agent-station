param(
    [string]$JavaHome = $env:JAVA_HOME
)

. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Get-RepoRoot
Set-LocalAppEnvironment -JavaHome $JavaHome

if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    throw '未检测到 OPENAI_API_KEY，Markdown Parent-Child 导入需要 embedding 能力。'
}

Push-Location $repoRoot
try {
    & mvn -q -pl ai-agent-station-app '-Dtest=OpenAiTest#uploadMarkdownParentChild' '-DRUN_REAL_AI_TESTS=true' '-DRUN_DB_MUTATION_TESTS=true' test
    if ($LASTEXITCODE -ne 0) {
        throw 'Markdown Parent-Child 导入失败。'
    }
    Write-Host 'Markdown Parent-Child 导入完成。'
} finally {
    Pop-Location
}
