param([switch]$Run, [switch]$Rehearse)
$ErrorActionPreference = 'Stop'
if ($Run -and $Rehearse) { throw 'Choose Run or Rehearse, not both.' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = Join-Path $projectRoot 'target/runtime-model-acceptance'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$readiness = [ordered]@{
    state = 'SETUP_REQUIRED'
    keyConfigured = -not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_OPENAI_API_KEY)
    modelConfigured = -not [string]::IsNullOrWhiteSpace($env:CHAT_EVAL_MODEL)
    plannedTurns = 8
    maxProviderRequests = 28
    turnOutputTokens = 800
    turnTimeoutSeconds = 20
    requestStartDeadlineSeconds = 180
    corpusSha256 = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'src/test/resources/chat/runtime-model-v1.json')).Hash.ToLowerInvariant()
}
if ($readiness.keyConfigured -and $readiness.modelConfigured) { $readiness.state = 'READY_TO_RUN' }
$readiness | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'readiness.json')
if (-not $Run -and -not $Rehearse) { $readiness | ConvertTo-Json; return }

$temporaryKey = $false
$temporaryModel = $false
$secureKey = $null
$runExit = 1
Push-Location -LiteralPath $projectRoot
try {
    if ($Run) {
        if (-not $readiness.keyConfigured) {
            $secureKey = Read-Host 'Dedicated evaluation API key (hidden; never paste in chat)' -AsSecureString
            if ($secureKey.Length -eq 0) { throw 'Evaluation key is required.' }
            $env:CHAT_EVAL_OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
            $temporaryKey = $true
        }
        if (-not $readiness.modelConfigured) {
            $env:CHAT_EVAL_MODEL = Read-Host 'Evaluation model (use the previously evaluated model for comparison)'
            $temporaryModel = $true
        }
        if ($env:CHAT_EVAL_MODEL -notmatch '^[a-zA-Z0-9._:-]{1,100}$') { throw 'A valid explicitly selected model is required.' }
    }
    $mode = if ($Run) { 'live' } else { 'rehearsal' }
    $log = Join-Path $outputRoot ($mode + '-' + (Get-Date -Format 'yyyyMMddHHmmssfff') + '.log')
    $arguments = @('-o', '-Dtest=RuntimeModelAcceptanceTest,RuntimeModelBudgetTest')
    if ($Run) { $arguments += '-DchatRuntimeModelLive=true' }
    else { $arguments += '-DchatRuntimeModelCatalog=true' }
    $arguments += 'test'
    # Windows PowerShell 5.1 turns native stderr into ErrorRecords; with Stop a JVM
    # warning can abort Tee-Object before LASTEXITCODE/report capture. Merge inside
    # cmd instead. Arguments are fixed above; neither API key nor model is in this command.
    # Push-Location above selects the project even when its path has spaces or '&'.
    $mavenCommand = '.\mvnw.cmd ' + ($arguments -join ' ') + ' 2>&1'
    # Script-local in PowerShell 7; Maven's exit code below remains authoritative.
    $PSNativeCommandUseErrorActionPreference = $false
    & $env:ComSpec /d /c $mavenCommand | Tee-Object -FilePath $log
    $runExit = $LASTEXITCODE
    [ordered]@{mode=$mode;exitCode=$runExit;log=$log;qualityAccepted=($Run.IsPresent -and $runExit -eq 0)} |
        ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $outputRoot 'launcher-last-run.json')
} finally {
    if ($temporaryKey) { Remove-Item Env:CHAT_EVAL_OPENAI_API_KEY -ErrorAction SilentlyContinue }
    if ($temporaryModel) { Remove-Item Env:CHAT_EVAL_MODEL -ErrorAction SilentlyContinue }
    if ($null -ne $secureKey) { $secureKey.Dispose() }
    Pop-Location
}
if ($runExit -ne 0) { throw 'Runtime evaluation failed or was incomplete. Inspect the sanitized report in target/runtime-model-acceptance.' }
