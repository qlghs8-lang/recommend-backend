param([switch]$Run)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot 'target/schema-d09-diagnosis-20261125'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$marker = Join-Path $outputRoot 'launch-started.json'
$existingResults = @(Get-ChildItem -LiteralPath $outputRoot -File | Where-Object { $_.Name -match '^live-[0-9]+\.json$' })
if ((Test-Path -LiteralPath $marker) -or $existingResults.Count -gt 0) {
    if ($Run) { throw 'D09 diagnosis was already launched. Preserve its result; review before any further call.' }
    [ordered]@{state='ALREADY_LAUNCHED_REVIEW_RESULT';productChanges=0} | ConvertTo-Json
    return
}
$priorPins = [ordered]@{
    'target/quality-baseline-20261125/pilot-20261125082720913747800.json' = 'd32f339ab407bd7736705ea17dc429e8736925df1fb3dd5f00264204a731122c'
    'target/schema-d10-diagnosis-20261125/live-20261125084417230961500.json' = 'eddbf74bdbfafe540f44a29e97d0edcbd61ff84faafbe933c644b0a0a174151f'
    'target/schema-d10-diagnosis-20261125/live-20261125091022637208400.json' = '2fc2315aca164710a8a5c2e9443b593f4f22faab5a2aee4c65b08a570f3162d8'
    'target/quality-remeasurement-20261125/pilot-20261125091500585131800.json' = 'ed69c1e659941ac2fd397a14b5aa44c5462f34f83424ac800a9a90c678f797ec'
}
$found = @()
foreach ($directory in @('target/quality-baseline-20261125','target/schema-d10-diagnosis-20261125','target/quality-remeasurement-20261125')) {
    $pattern = if ($directory -like '*schema-d10*') { '^live-[0-9]+\.json$' } else { '^pilot-[0-9]+\.json$' }
    $found += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -File | Where-Object { $_.Name -match $pattern })
}
if ($found.Count -ne $priorPins.Count) { throw 'Unexpected prior run count; review cumulative reservations first.' }
[decimal]$prior = 0
$pinnedInputs = @()
foreach ($relative in $priorPins.Keys) {
    $path = Join-Path $projectRoot $relative
    $hash = (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
    if ($hash -ne $priorPins[$relative]) { throw 'Frozen prior report changed; inspect before any provider call.' }
    $report = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($report.model -ne 'gpt-6-luna') { throw 'Prior model does not match the authorized ledger.' }
    $prior += [decimal]$report.reservationEstimateUsd
    $pinnedInputs += [ordered]@{path=$relative;sha256=$hash}
}
[double]$cap = 0.10
if (-not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_MAX_USD)) {
    [double]$existing = 0
    if (-not [double]::TryParse($env:CHAT_EVAL_MAX_USD, [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture, [ref]$existing) -or
        [double]::IsNaN($existing) -or [double]::IsInfinity($existing) -or $existing -le 0 -or $existing -gt 1) {
        throw 'Existing evaluation ceiling must be (0,1] USD.'
    }
    $cap = [Math]::Min($cap, $existing)
}
if ($prior -lt 0 -or $prior + [decimal]$cap -gt 1) { throw 'Cumulative approved estimate would be exceeded.' }
$fixtureRelative = 'src/test/resources/chat/schema-d09-original-v1.json'
if ((Get-FileHash -LiteralPath (Join-Path $projectRoot $fixtureRelative)).Hash.ToLowerInvariant() -ne '46ec07b294421bbc47089c4f60e4abc3e9d83086eb841b27142f6e673d8b5d09') {
    throw 'Pinned D09 public schema changed; inspect before any provider call.'
}
foreach ($relative in @($fixtureRelative,
    'src/test/java/com/example/recommend/chat/config/SchemaD09DiagnosisTest.java',
    'src/test/java/com/example/recommend/chat/config/SchemaD10DiagnosisTest.java',
    'src/test/java/com/example/recommend/chat/config/QualityPilotBudget.java',
    'src/main/java/com/example/recommend/chat/config/AiConfig.java',
    'src/main/java/com/example/recommend/chat/config/AiProperties.java',
    'scripts/run_schema_d09_diagnosis.ps1')) {
    $pinnedInputs += [ordered]@{path=$relative;sha256=(Get-FileHash -LiteralPath (Join-Path $projectRoot $relative)).Hash.ToLowerInvariant()}
}
[ordered]@{protocol='D09_FIXED_QUOTE_CHARACTER_PROBE_V1';model='gpt-6-luna';maxRequests=4;
    priorReservationEstimateUsd=$prior;maxEstimatedUsd=$cap;cumulativeMaxEstimatedUsd=($prior + [decimal]$cap);
    priorReports=@($priorPins.Keys);pinnedInputs=$pinnedInputs;billingTotalKnown=$false} |
    ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'run-plan.json')
$keyConfigured = -not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_OPENAI_API_KEY)
$readiness = [ordered]@{state='PRIVATE_KEY_REQUIRED';keyConfigured=$keyConfigured;model='gpt-6-luna';
    maxRequests=4;maxEstimatedUsd=$cap;priorReservationEstimateUsd=$prior;cumulativeMaxEstimatedUsd=($prior + [decimal]$cap);
    productChanges=0;expectedTestClass='SchemaD09DiagnosisTest'}
if ($keyConfigured) { $readiness.state = 'READY_FOR_EXPLICIT_D09_DIAGNOSIS' }
$readiness | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'readiness.json')
if (-not $Run) { $readiness | ConvertTo-Json; return }
$temporaryKey = $false
$temporaryModel = $false
$previousCeiling = $env:CHAT_EVAL_MAX_USD
$secureKey = $null
$runExit = 1
Push-Location -LiteralPath $projectRoot
try {
    if ([string]::IsNullOrWhiteSpace($env:CHAT_EVAL_MODEL)) { $env:CHAT_EVAL_MODEL = 'gpt-6-luna'; $temporaryModel = $true }
    if ($env:CHAT_EVAL_MODEL -ne 'gpt-6-luna') { throw 'Use the explicitly approved model only.' }
    $env:CHAT_EVAL_MAX_USD = $cap.ToString('R', [Globalization.CultureInfo]::InvariantCulture)
    if (-not $keyConfigured) {
        $secureKey = Read-Host 'Dedicated evaluation API key for D09 diagnosis (hidden; never paste in chat)' -AsSecureString
        if ($secureKey.Length -eq 0) { throw 'Dedicated evaluation key is required.' }
        $env:CHAT_EVAL_OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
        $temporaryKey = $true
    }
    $stream = [IO.File]::Open($marker, [IO.FileMode]::CreateNew)
    $stream.Dispose()
    [ordered]@{state='STARTED_DO_NOT_RETRY';model='gpt-6-luna';maxEstimatedUsd=$cap;startedAtUtc=[DateTime]::UtcNow.ToString('o')} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $marker
    $log = Join-Path $outputRoot ('launch-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.log')
    Write-Host 'D09: four fixed schema probes only. Expected test class: SchemaD09DiagnosisTest.'
    $PSNativeCommandUseErrorActionPreference = $false
    & $env:ComSpec /d /c '.\mvnw.cmd -o -Dtest=SchemaD09DiagnosisTest -DschemaD09Live=true test 2>&1' |
        Tee-Object -FilePath $log
    $runExit = $LASTEXITCODE
    [ordered]@{exitCode=$runExit;log=$log;state='D09_DIAGNOSIS_REQUIRES_REVIEW'} | ConvertTo-Json |
        Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'launcher-last-run.json')
} finally {
    if ($temporaryKey) { Remove-Item Env:CHAT_EVAL_OPENAI_API_KEY -ErrorAction SilentlyContinue }
    if ($temporaryModel) { Remove-Item Env:CHAT_EVAL_MODEL -ErrorAction SilentlyContinue }
    if ($null -eq $previousCeiling) { Remove-Item Env:CHAT_EVAL_MAX_USD -ErrorAction SilentlyContinue }
    else { $env:CHAT_EVAL_MAX_USD = $previousCeiling }
    if ($null -ne $secureKey) { $secureKey.Dispose() }
    Pop-Location
}
if ($runExit -ne 0) { throw 'Fixed D09 diagnosis failed or was incomplete. Preserve its report; do not rerun automatically.' }
