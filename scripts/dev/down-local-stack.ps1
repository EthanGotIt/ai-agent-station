param(
    [switch]$WithVolumes
)

. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Get-RepoRoot
$composePath = Get-ComposePath

Assert-DockerReady

Push-Location $repoRoot
try {
    $args = @('compose', '-f', $composePath, 'down')
    if ($WithVolumes) {
        $args += '--volumes'
    }
    & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw '关闭本地依赖失败。'
    }
} finally {
    Pop-Location
}
