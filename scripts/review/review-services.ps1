param(
    [ValidateSet("start", "stop", "status")]
    [string]$Command = "status",
    [switch]$WithFixture,
    [ValidateRange(0, 20)]
    [int]$ExpediteTransientFailures = 3
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$StateRoot = Join-Path ([System.IO.Path]::GetTempPath()) "commerce-guardian-agent-review"
$StateFile = Join-Path $StateRoot "processes.json"
$LogRoot = Join-Path $StateRoot "logs"
$null = New-Item -ItemType Directory -Force -Path $LogRoot

function Read-State {
    if (-not (Test-Path -LiteralPath $StateFile)) {
        return @()
    }
    try {
        $parsed = Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
        if ($parsed -is [array]) { return @($parsed) }
        return @($parsed)
    } catch {
        Write-Warning "Cannot read review process state; treating it as empty: $($_.Exception.Message)"
        return @()
    }
}

function Write-State([object[]]$Entries) {
    $Entries | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $StateFile -Encoding UTF8
}

function Test-ProcessAlive([int]$ProcessId) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    return $null -ne $process -and -not $process.HasExited
}

function Get-ProcessCommandLine([int]$ProcessId) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    return [string]$process.CommandLine
}

function Test-ReviewProcessIdentity([object]$Entry) {
    if ($null -eq $Entry -or -not (Test-ProcessAlive ([int]$Entry.pid))) {
        return $false
    }
    # 旧状态文件没有命令签名，宁可标记为过期，也不凭 PID 猜测进程归属。
    $signature = [string]$Entry.commandSignature
    if ([string]::IsNullOrWhiteSpace($signature)) {
        return $false
    }
    $commandLine = Get-ProcessCommandLine ([int]$Entry.pid)
    if ([string]::IsNullOrWhiteSpace($commandLine)) {
        return $false
    }
    $tokens = @($signature -split '\s+') | Select-Object -Skip 1 | Where-Object {
        $_ -and $_.Length -ge 4 -and $_ -notmatch '^(--?\w+)$'
    }
    foreach ($token in $tokens) {
        $candidate = $token.Trim('"')
        if (-not $commandLine.Contains($candidate, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $false
        }
    }
    return $true
}

function Start-ReviewProcess(
    [string]$Name,
    [string]$FilePath,
    [string[]]$Arguments,
    [int]$Port
) {
    $allState = @(Read-State)
    $existing = $allState | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    $state = @($allState | Where-Object { $_.name -ne $Name })
    if ($null -ne $existing -and (Test-ReviewProcessIdentity $existing)) {
        Write-Host "$Name already running PID=$($existing.pid) port=$Port"
        return @($state + $existing)
    }

    $stdout = Join-Path $LogRoot "$Name.out.log"
    $stderr = Join-Path $LogRoot "$Name.err.log"
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $Root `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $entry = [pscustomobject]@{
        name = $Name
        pid = $process.Id
        port = $Port
        startedAt = [DateTime]::UtcNow.ToString("o")
        stdout = $stdout
        stderr = $stderr
        commandSignature = "$FilePath $($Arguments -join ' ')"
    }
    Write-Host "$Name started PID=$($process.Id) port=$Port"
    return @($state + $entry)
}

function Start-Services {
    $state = @(Read-State | Where-Object { -not (Test-ProcessAlive ([int]$_.pid)) })

    if ($WithFixture) {
        $env:ORDER_SERVICE_HOST = "127.0.0.1"
        $env:ORDER_SERVICE_PORT = "18080"
        $env:ORDER_SERVICE_DATABASE_PATH = Join-Path $StateRoot "order-service.db"
        $env:ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES = [string]$ExpediteTransientFailures
        $state = @(Start-ReviewProcess "order-fixture" "python.exe" @(
                "scripts/acceptance/order_service_fixture/server.py"
            ) 18080)
        Write-State $state
    }

    $env:SERVER_PORT = "8090"
    if ($WithFixture) {
        $env:AI_AGENT_ORDER_GATEWAY = "http"
        $env:AI_AGENT_ORDER_BASE_URL = "http://127.0.0.1:18080"
    }
    & mvn.cmd -pl commerce-guardian-agent-app -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Agent app package failed with exit code $LASTEXITCODE"
    }
    $applicationJar = Get-ChildItem -LiteralPath (Join-Path $Root "commerce-guardian-agent-app\target") `
        -Filter "*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
    if ($null -eq $applicationJar) {
        throw "Agent app jar was not produced"
    }
    $state = @(Start-ReviewProcess "agent-app" "java.exe" @(
            "-jar", $applicationJar.FullName
        ) 8090)
    Write-State $state

    $state = @(Start-ReviewProcess "agent-fronted" "npm.cmd" @(
            "--prefix", "agent-fronted", "run", "dev", "--", "--host", "127.0.0.1", "--port", "5173"
        ) 5173)
    Write-State $state
    Write-Output "Logs: $LogRoot"
}

function Stop-Services {
    $state = @(Read-State)
    foreach ($entry in $state) {
        $processId = [int]$entry.pid
        if (-not (Test-ReviewProcessIdentity $entry)) {
            Write-Output "$($entry.name) skipped PID=$processId (stale or command identity mismatch)"
            continue
        }
        if (Test-ProcessAlive $processId) {
            & taskkill.exe /PID $processId /T /F | Out-Null
            Write-Output "$($entry.name) stopped PID=$processId"
        } else {
            Write-Output "$($entry.name) was not running PID=$processId"
        }
    }
    Remove-Item -LiteralPath $StateFile -Force -ErrorAction SilentlyContinue
}

function Show-Status {
    $state = @(Read-State)
    if ($state.Count -eq 0) {
        Write-Output "No review-managed services are recorded."
        return
    }
    foreach ($entry in $state) {
        $alive = Test-ProcessAlive ([int]$entry.pid)
        $owned = Test-ReviewProcessIdentity $entry
        $stateLabel = if ($owned) { "RUNNING" } elseif ($alive) { "STALE" } else { "STOPPED" }
        Write-Output ("{0}: {1} PID={2} port={3} log={4}" -f $entry.name, $stateLabel, $entry.pid, $entry.port, $entry.stdout)
    }
}

switch ($Command) {
    "start" { Start-Services }
    "stop" { Stop-Services }
    "status" { Show-Status }
}
