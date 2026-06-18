$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Use-Java17 {
    param(
        [string]$JavaHome
    )

    $candidates = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
        $candidates.Add($JavaHome)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and ($env:JAVA_HOME -ne $JavaHome)) {
        $candidates.Add($env:JAVA_HOME)
    }
    $candidates.Add('D:\Environment\JDK17')

    $resolvedJavaHome = $null
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }

        $javaExe = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path $javaExe)) {
            continue
        }

        $versionOutput = & $javaExe -version 2>&1
        if ($versionOutput -match 'version "17(\.|$)') {
            $resolvedJavaHome = $candidate
            break
        }
    }

    if ([string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
        throw '未找到可用的 JDK17，请确认 D:\Environment\JDK17 可用，或显式传入 -JavaHome。'
    }

    $env:JAVA_HOME = $resolvedJavaHome
    if (-not $env:Path.StartsWith("$resolvedJavaHome\bin;", [System.StringComparison]::OrdinalIgnoreCase)) {
        $env:Path = "$resolvedJavaHome\bin;$($env:Path)"
    }
}

function Set-LocalAppEnvironment {
    param(
        [string]$JavaHome = $env:JAVA_HOME
    )

    Use-Java17 -JavaHome $JavaHome
    $env:MYSQL_URL = if ($env:MYSQL_URL) { $env:MYSQL_URL } else { 'jdbc:mysql://127.0.0.1:3306/ai-agent-station?useUnicode=true&characterEncoding=utf8&autoReconnect=true&zeroDateTimeBehavior=convertToNull&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' }
    $env:MYSQL_USERNAME = if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { 'root' }
    $env:MYSQL_PASSWORD = if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { '123456' }
    $env:PGVECTOR_URL = if ($env:PGVECTOR_URL) { $env:PGVECTOR_URL } else { 'jdbc:postgresql://127.0.0.1:5432/ai-agent-station' }
    $env:PGVECTOR_USERNAME = if ($env:PGVECTOR_USERNAME) { $env:PGVECTOR_USERNAME } else { 'postgres' }
    $env:PGVECTOR_PASSWORD = if ($env:PGVECTOR_PASSWORD) { $env:PGVECTOR_PASSWORD } else { 'postgres' }
    $env:AI_AGENT_ES_BASE_URL = if ($env:AI_AGENT_ES_BASE_URL) { $env:AI_AGENT_ES_BASE_URL } else { 'http://127.0.0.1:9200' }
    $env:AI_AGENT_VECTOR_STORE_ENABLED = 'true'
    $env:AI_AGENT_VECTOR_STORE_BASE_URL = if ($env:AI_AGENT_VECTOR_STORE_BASE_URL) { $env:AI_AGENT_VECTOR_STORE_BASE_URL } else { 'https://dashscope.aliyuncs.com/compatible-mode/v1' }
    $env:AI_AGENT_VECTOR_STORE_MODEL = if ($env:AI_AGENT_VECTOR_STORE_MODEL) { $env:AI_AGENT_VECTOR_STORE_MODEL } else { 'text-embedding-v4' }
    $env:AI_AGENT_VECTOR_STORE_DIMENSIONS = if ($env:AI_AGENT_VECTOR_STORE_DIMENSIONS) { $env:AI_AGENT_VECTOR_STORE_DIMENSIONS } else { '1024' }
    $env:AI_AGENT_CONTEXT_MAX_CHARS = if ($env:AI_AGENT_CONTEXT_MAX_CHARS) { $env:AI_AGENT_CONTEXT_MAX_CHARS } else { '4000' }
    $env:AI_AGENT_CONTEXT_COMPRESS_THRESHOLD = if ($env:AI_AGENT_CONTEXT_COMPRESS_THRESHOLD) { $env:AI_AGENT_CONTEXT_COMPRESS_THRESHOLD } else { '0.70' }
    $env:RUN_REAL_AI_TESTS = 'true'
    $env:RUN_DB_MUTATION_TESTS = 'true'
}

function Assert-DockerReady {
    & docker info 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop 未就绪，请先启动 Docker Desktop 后重试。'
    }
}

function Assert-TcpPortReady {
    param(
        [Parameter(Mandatory = $true)][string]$TargetHost,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $result = Test-NetConnection $TargetHost -Port $Port -WarningAction SilentlyContinue
        if ($result.TcpTestSucceeded) {
            return
        }
        Start-Sleep -Seconds 2
    }

    throw "等待端口超时：$TargetHost`:$Port"
}

function Ensure-ContainerRunning {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerName,
        [switch]$AllowHealthyOnly
    )

    $container = docker ps -a --format '{{.Names}}' | Where-Object { $_ -eq $ContainerName } | Select-Object -First 1
    if (-not $container) {
        throw "未找到容器：$ContainerName，请先按既有 dev-ops 方式创建该容器。"
    }

    $status = docker inspect --format '{{.State.Status}}' $ContainerName 2>$null
    $status = ($status | Select-Object -First 1).Trim()
    if ($status -ne 'running') {
        & docker start $ContainerName | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "启动容器失败：$ContainerName"
        }
    }

    Wait-ContainerHealthy -ContainerName $ContainerName
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

function Reset-PgVectorSchema {
    param(
        [string]$ContainerName = 'pgvector',
        [string]$DatabaseName = 'ai-agent-station',
        [string]$Username = 'postgres',
        [string]$SqlFile = (Join-Path (Get-RepoRoot) 'docs\dev-ops\pgvector\sql\ai-agent-station.sql')
    )

    if (-not (Test-Path $SqlFile)) {
        throw "未找到 PgVector 初始化 SQL：$SqlFile"
    }

    Ensure-ContainerRunning -ContainerName $ContainerName
    Get-Content -Raw -Path $SqlFile |
        docker exec -i $ContainerName psql -U $Username -d $DatabaseName -v ON_ERROR_STOP=1 -f - | Out-Host

    if ($LASTEXITCODE -ne 0) {
        throw '重建 PgVector schema 失败。'
    }
}
