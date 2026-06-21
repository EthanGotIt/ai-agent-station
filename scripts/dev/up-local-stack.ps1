. (Join-Path $PSScriptRoot 'common.ps1')

Assert-DockerReady
Assert-LocalPostgresStopped
Set-LocalAppEnvironment

Assert-TcpPortReady -TargetHost '127.0.0.1' -Port 3306 -TimeoutSeconds 15
Ensure-ContainerRunning -ContainerName 'pgvector'
Ensure-ContainerRunning -ContainerName 'elasticsearch'
Assert-TcpPortReady -TargetHost '127.0.0.1' -Port 5432 -TimeoutSeconds 15
Assert-TcpPortReady -TargetHost '127.0.0.1' -Port 9200 -TimeoutSeconds 15

Write-Host '已复用现有本地环境：MySQL(3306) + Docker pgvector(5432) + Docker elasticsearch(9200)。'
Write-Host '如需停止额外观测容器，请手工关闭 kibana / logstash / grafana / prometheus。'
