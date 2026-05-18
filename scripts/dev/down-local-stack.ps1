param(
    [switch]$StopObservability
)

. (Join-Path $PSScriptRoot 'common.ps1')

Assert-DockerReady
foreach ($containerName in @('pgvector', 'elasticsearch')) {
    $exists = docker ps -a --format '{{.Names}}' | Where-Object { $_ -eq $containerName } | Select-Object -First 1
    if ($exists) {
        & docker stop $containerName | Out-Null
    }
}

if ($StopObservability) {
    foreach ($containerName in @('kibana', 'logstash', 'grafana', 'prometheus')) {
        $exists = docker ps -a --format '{{.Names}}' | Where-Object { $_ -eq $containerName } | Select-Object -First 1
        if ($exists) {
            & docker stop $containerName | Out-Null
        }
    }
}

Write-Host '已停止复用环境中的核心容器：pgvector / elasticsearch。'
