param([switch]$Run)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot 'target/schema-d10-diagnosis-20261125'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$fixture = Join-Path $projectRoot 'src/test/resources/chat/schema-d10-original-v1.json'
if ((Get-FileHash -LiteralPath $fixture).Hash.ToLowerInvariant() -ne 'a17426126d3df3266172cf49539b0edd95e9fc87c9cd2f35a6742dbd41665f25') {
    throw 'Pinned public schema changed; inspect before any provider request.'
}
$keyConfigured = -not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_OPENAI_API_KEY)
$readiness = [ordered]@{state='PRIVATE_KEY_REQUIRED';keyConfigured=$keyConfigured;model='gpt-6-luna';
    maxRequests=4;maxEstimatedUsd=0.10;outputTokensPerRequest=128;productChanges=0}
if ($keyConfigured) { $readiness.state = 'READY_FOR_EXPLICIT_DIAGNOSIS' }
$readiness | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'readiness.json')
if (-not $Run) { $readiness | ConvertTo-Json; return }
$temporaryKey = $false
$temporaryModel = $false
$secureKey = $null
$runExit = 1
Push-Location -LiteralPath $projectRoot
try {
    if (-not $keyConfigured) {
        $secureKey = Read-Host 'Dedicated evaluation API key (hidden; never paste in chat)' -AsSecureString
        if ($secureKey.Length -eq 0) { throw 'Dedicated evaluation key is required.' }
        $env:CHAT_EVAL_OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
        $temporaryKey = $true
    }
    if ([string]::IsNullOrWhiteSpace($env:CHAT_EVAL_MODEL)) {
        $env:CHAT_EVAL_MODEL = 'gpt-6-luna'
        $temporaryModel = $true
    }
    if ($env:CHAT_EVAL_MODEL -ne 'gpt-6-luna') { throw 'Keep the explicitly selected model; no automatic substitution.' }
    $log = Join-Path $outputRoot ('launch-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.log')
    $PSNativeCommandUseErrorActionPreference = $false
    # No credentials or user-provided strings are interpolated into this fixed command.
    & $env:ComSpec /d /c '.\mvnw.cmd -o -Dtest=SchemaD10DiagnosisTest -DschemaD10Live=true test 2>&1' |
        Tee-Object -FilePath $log
    $runExit = $LASTEXITCODE
    [ordered]@{exitCode=$runExit;log=$log;diagnosisRequiresReview=$true} | ConvertTo-Json |
        Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'launcher-last-run.json')
} finally {
    if ($temporaryKey) { Remove-Item Env:CHAT_EVAL_OPENAI_API_KEY -ErrorAction SilentlyContinue }
    if ($temporaryModel) { Remove-Item Env:CHAT_EVAL_MODEL -ErrorAction SilentlyContinue }
    if ($null -ne $secureKey) { $secureKey.Dispose() }
    Pop-Location
}
if ($runExit -ne 0) { throw 'Fixed diagnosis failed or was incomplete. Preserve its report; do not rerun automatically.' }
