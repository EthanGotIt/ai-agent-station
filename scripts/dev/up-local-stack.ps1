. (Join-Path $PSScriptRoot 'common.ps1')

Assert-DockerReady
Set-LocalAppEnvironment

Assert-TcpPortReady -TargetHost '127.0.0.1' -Port 3306 -TimeoutSeconds 15
Ensure-ContainerRunning -ContainerName 'pgvector'
Ensure-ContainerRunning -ContainerName 'elasticsearch'

Write-Host '已复用现有本地环境：MySQL(3306) + pgvector(5432) + elasticsearch(9200)。'
Write-Host '如需停止额外观测容器，请手工关闭 kibana / logstash / grafana / prometheus。'
