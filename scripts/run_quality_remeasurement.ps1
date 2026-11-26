param([switch]$Run)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot 'target/quality-remeasurement-20261125'
$planJson = & python (Join-Path $PSScriptRoot 'prepare_quality_remeasurement.py')
if ($LASTEXITCODE -ne 0) { throw 'Remeasurement preflight failed; inspect public inputs and prior runs.' }
$plan = $planJson | ConvertFrom-Json
$keyConfigured = -not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_OPENAI_API_KEY)
$readiness = [ordered]@{state='PRIVATE_KEY_REQUIRED';keyConfigured=$keyConfigured;model='gpt-6-luna';
    selectedTurns=12;maxProviderRequests=60;priorReservationEstimateUsd=$plan.priorReservationEstimateUsd;
    maxEstimatedUsd=$plan.runMaxEstimatedUsd;cumulativeMaxEstimatedUsd=$plan.cumulativeMaxEstimatedUsd;qualityAccepted=$false}
if ($keyConfigured) { $readiness.state = 'READY_FOR_EXPLICIT_REMEASUREMENT' }
$readiness | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'readiness.json')
if (-not $Run) { $readiness | ConvertTo-Json; return }
$temporaryKey = $false
$temporaryModel = $false
$previousCeiling = $env:CHAT_EVAL_MAX_USD
$secureKey = $null
$runExit = 1
Push-Location -LiteralPath $projectRoot
try {
    if ([string]::IsNullOrWhiteSpace($env:CHAT_EVAL_MODEL)) {
        $env:CHAT_EVAL_MODEL = 'gpt-6-luna'
        $temporaryModel = $true
    }
    if ($env:CHAT_EVAL_MODEL -ne 'gpt-6-luna') { throw 'Use the explicitly approved model only.' }
    [double]$cap = $plan.runMaxEstimatedUsd
    if (-not [string]::IsNullOrWhiteSpace($previousCeiling)) {
        [double]$existing = 0
        if (-not [double]::TryParse($previousCeiling, [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$existing) -or
            [double]::IsNaN($existing) -or [double]::IsInfinity($existing) -or $existing -le 0 -or $existing -gt 1) {
            throw 'Existing evaluation ceiling must be (0,1] USD.'
        }
        $cap = [Math]::Min($cap, $existing)
    }
    $env:CHAT_EVAL_MAX_USD = $cap.ToString('R', [Globalization.CultureInfo]::InvariantCulture)
    if (-not $keyConfigured) {
        $secureKey = Read-Host 'Dedicated evaluation API key (hidden; never paste in chat)' -AsSecureString
        if ($secureKey.Length -eq 0) { throw 'Dedicated evaluation key is required.' }
        $env:CHAT_EVAL_OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
        $temporaryKey = $true
    }
    $marker = Join-Path $outputRoot 'launch-started.json'
    $stream = [IO.File]::Open($marker, [IO.FileMode]::CreateNew)
    $stream.Dispose()
    [ordered]@{state='STARTED_DO_NOT_RETRY';model='gpt-6-luna';maxEstimatedUsd=$cap;startedAtUtc=[DateTime]::UtcNow.ToString('o')} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $marker
    $log = Join-Path $outputRoot ('launch-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.log')
    $PSNativeCommandUseErrorActionPreference = $false
    & $env:ComSpec /d /c '.\mvnw.cmd -o -Dtest=QualityBaselineLiveTest -DqualityBaselineLive=true -DqualityBaselineRemeasurement=true test 2>&1' |
        Tee-Object -FilePath $log
    $runExit = $LASTEXITCODE
    [ordered]@{exitCode=$runExit;log=$log;qualityAccepted=$false;state='REMEASUREMENT_REQUIRES_REVIEW'} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'launcher-last-run.json')
} finally {
    if ($temporaryKey) { Remove-Item Env:CHAT_EVAL_OPENAI_API_KEY -ErrorAction SilentlyContinue }
    if ($temporaryModel) { Remove-Item Env:CHAT_EVAL_MODEL -ErrorAction SilentlyContinue }
    if ($null -eq $previousCeiling) { Remove-Item Env:CHAT_EVAL_MAX_USD -ErrorAction SilentlyContinue }
    else { $env:CHAT_EVAL_MAX_USD = $previousCeiling }
    if ($null -ne $secureKey) { $secureKey.Dispose() }
    Pop-Location
}
if ($runExit -ne 0) { throw 'Remeasurement failed or was incomplete. Preserve the report; do not rerun automatically.' }
