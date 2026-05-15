$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Get-ComposePath {
    return Join-Path (Get-RepoRoot) 'docs\dev-ops\docker-compose-local.yml'
}

function Use-Java17 {
    param(
        [string]$JavaHome = $env:JAVA_HOME
    )

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        $JavaHome = 'D:\Environment\JDK17'
    }

    if (-not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
        throw "未找到可用的 JDK17：$JavaHome"
    }

    $env:JAVA_HOME = $JavaHome
    if (-not $env:Path.StartsWith("$JavaHome\bin;", [System.StringComparison]::OrdinalIgnoreCase)) {
        $env:Path = "$JavaHome\bin;$($env:Path)"
    }
}

function Set-LocalAppEnvironment {
    param(
        [string]$JavaHome = $env:JAVA_HOME
    )

    Use-Java17 -JavaHome $JavaHome
    $env:MYSQL_URL = 'jdbc:mysql://127.0.0.1:13306/ai-agent-station?useUnicode=true&characterEncoding=utf8&autoReconnect=true&zeroDateTimeBehavior=convertToNull&serverTimezone=Asia/Shanghai&useSSL=false'
    $env:MYSQL_USERNAME = 'root'
    $env:MYSQL_PASSWORD = '123456'
    $env:PGVECTOR_URL = 'jdbc:postgresql://127.0.0.1:15432/ai-agent-station'
    $env:PGVECTOR_USERNAME = 'postgres'
    $env:PGVECTOR_PASSWORD = 'postgres'
    $env:AI_AGENT_ES_BASE_URL = 'http://127.0.0.1:19200'
    $env:AI_AGENT_VECTOR_STORE_ENABLED = 'true'
    $env:AI_AGENT_CONTEXT_MAX_CHARS = if ($env:AI_AGENT_CONTEXT_MAX_CHARS) { $env:AI_AGENT_CONTEXT_MAX_CHARS } else { '4000' }
    $env:AI_AGENT_CONTEXT_COMPRESS_THRESHOLD = if ($env:AI_AGENT_CONTEXT_COMPRESS_THRESHOLD) { $env:AI_AGENT_CONTEXT_COMPRESS_THRESHOLD } else { '0.70' }
    $env:RUN_REAL_AI_TESTS = 'true'
    $env:RUN_DB_MUTATION_TESTS = 'true'
}

function Assert-DockerReady {
    try {
        docker info | Out-Null
    } catch {
        throw 'Docker Desktop 未就绪，请先启动 Docker Desktop 后重试。'
    }
}

function Get-ContainerHealthStatus {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName
    )

    $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ContainerName 2>$null
    return ($status | Select-Object -First 1).Trim()
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = Get-ContainerHealthStatus -ContainerName $ContainerName
        if ($status -eq 'healthy' -or $status -eq 'running') {
            Write-Host "容器已就绪：$ContainerName ($status)"
            return
        }
        Start-Sleep -Seconds 3
    }

    throw "等待容器超时：$ContainerName"
}

function Assert-HttpOk {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 2
    }

    throw "等待 HTTP 服务超时：$Url"
}
