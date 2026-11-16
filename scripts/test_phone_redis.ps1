$ErrorActionPreference = 'Stop'
$testContainer = 'recommend-phone-test-' + [Guid]::NewGuid().ToString('N')
$testContainerId = $null
$previousFlag = $env:RUN_PHONE_REDIS_TESTS
$projectPath = Split-Path -Parent $PSScriptRoot
Push-Location $projectPath
try {
    # Desktop can have running processes while its engine is unresponsive.
    $engineCheck = Start-Job { docker version --format '{{.Server.Version}}'; exit $LASTEXITCODE }
    try {
        if (-not (Wait-Job $engineCheck -Timeout 20)) {
            throw 'Docker engine did not respond within 20 seconds. Resolve the Docker Desktop startup error first.'
        }
        $engineVersion = Receive-Job $engineCheck -ErrorAction Stop
        if (-not ($engineVersion -match '^\d+\.\d+')) { throw 'Docker engine is not ready.' }
    } finally {
        if ($engineCheck.State -eq 'Running') { Stop-Job $engineCheck }
        Remove-Job $engineCheck
    }
    # No application database, compose stack, existing Redis or persistent volume is used.
    $testContainerId = & docker run --detach --rm --name $testContainer `
        --publish '127.0.0.1:16379:6379' redis:7-alpine `
        redis-server --save '' --appendonly no --maxmemory 64mb --maxmemory-policy noeviction
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated Redis; check Docker and port 16379.' }
    $testContainerId = $testContainerId.Trim()
    if ($testContainerId -notmatch '^[a-f0-9]{64}$') { throw 'Unexpected container ID.' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $reply = & docker exec $testContainerId redis-cli ping 2>$null
        if ($LASTEXITCODE -eq 0 -and $reply -eq 'PONG') { $ready = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Isolated Redis did not become ready.' }
    $env:RUN_PHONE_REDIS_TESTS = 'true'
    & .\mvnw.cmd test '-Dtest=RedisPhoneVerificationTest' '-Dsurefire.reportsDirectory=target/redis-phone-reports'
    if ($LASTEXITCODE -ne 0) { throw 'Redis phone integration tests failed.' }
} finally {
    $env:RUN_PHONE_REDIS_TESTS = $previousFlag
    if ($testContainerId -match '^[a-f0-9]{64}$') {
        # Remove only the exact disposable container created by this invocation.
        & docker stop $testContainerId | Out-Null
    }
    Pop-Location
}
