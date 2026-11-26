param([switch]$Run)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot 'target/quality-baseline-20261125'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$readiness = [ordered]@{
    state = 'SETUP_REQUIRED'
    keyConfigured = -not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_OPENAI_API_KEY)
    modelConfigured = ($env:CHAT_EVAL_MODEL -eq 'gpt-6-luna')
    ceilingConfigured = -not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_MAX_USD)
    publicSnapshotPresent = Test-Path -LiteralPath (Join-Path $outputRoot 'public-snapshot.json')
    selectedTurns = 12
    maxProviderRequests = 60
    approvedModel = 'gpt-6-luna'
    approvedMaxUsd = 1
    qualityAccepted = $false
}
if ($readiness.keyConfigured -and $readiness.modelConfigured -and $readiness.ceilingConfigured -and $readiness.publicSnapshotPresent) {
    $readiness.state = 'READY_FOR_EXPLICIT_PILOT'
}
$readiness | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'pilot-readiness.json')
if (-not $Run) {
    $readiness | ConvertTo-Json
    return
}
if (-not $readiness.publicSnapshotPresent) { throw 'Prepare the read-only public snapshot first.' }
$temporaryKey = $false
$temporaryModel = $false
$temporaryCeiling = $false
$secureKey = $null
$runExit = 1
Push-Location -LiteralPath $projectRoot
try {
    if (-not $readiness.keyConfigured) {
        $secureKey = Read-Host 'Dedicated evaluation API key (hidden; never paste in chat)' -AsSecureString
        if ($secureKey.Length -eq 0) { throw 'Dedicated evaluation key is required.' }
        $env:CHAT_EVAL_OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
        $temporaryKey = $true
    }
    if ([string]::IsNullOrWhiteSpace($env:CHAT_EVAL_MODEL)) {
        $env:CHAT_EVAL_MODEL = 'gpt-6-luna' # Explicit user selection in this session.
        $temporaryModel = $true
    }
    if ($env:CHAT_EVAL_MODEL -ne 'gpt-6-luna') { throw 'Model must match the reviewed pricing; no automatic substitution.' }
    if (-not $readiness.ceilingConfigured) {
        $env:CHAT_EVAL_MAX_USD = '1' # User-authorized ceiling; never raise an existing lower limit.
        $temporaryCeiling = $true
    }
    [double]$pilotCeiling = 0
    if (-not [double]::TryParse($env:CHAT_EVAL_MAX_USD, [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture, [ref]$pilotCeiling) -or
        [double]::IsNaN($pilotCeiling) -or [double]::IsInfinity($pilotCeiling) -or $pilotCeiling -le 0 -or $pilotCeiling -gt 1) {
        throw 'Pilot ceiling must be greater than 0 and at most 1 USD.'
    }
    $log = Join-Path $outputRoot ('pilot-launch-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.log')
    $PSNativeCommandUseErrorActionPreference = $false
    # Fixed command only. No key/model/ceiling is interpolated into shell arguments.
    & $env:ComSpec /d /c '.\mvnw.cmd -o -Dtest=QualityBaselineLiveTest -DqualityBaselineLive=true test 2>&1' |
        Tee-Object -FilePath $log
    $runExit = $LASTEXITCODE
    [ordered]@{exitCode=$runExit;log=$log;qualityAccepted=$false;state='PILOT_REQUIRES_REVIEW'} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'pilot-launcher-last-run.json')
} finally {
    if ($temporaryKey) { Remove-Item Env:CHAT_EVAL_OPENAI_API_KEY -ErrorAction SilentlyContinue }
    if ($temporaryModel) { Remove-Item Env:CHAT_EVAL_MODEL -ErrorAction SilentlyContinue }
    if ($temporaryCeiling) { Remove-Item Env:CHAT_EVAL_MAX_USD -ErrorAction SilentlyContinue }
    if ($null -ne $secureKey) { $secureKey.Dispose() }
    Pop-Location
}
if ($runExit -ne 0) { throw 'Pilot failed or was incomplete; inspect its sanitized report. Do not silently rerun.' }
