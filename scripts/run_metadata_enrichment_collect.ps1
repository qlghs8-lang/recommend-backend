param([switch]$Collect)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = Join-Path $projectRoot 'target/metadata-enrichment-live'
$mode = if ($Collect) { 'collect' } else { 'prepare' }
$runName = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$relativeOutput = 'target/metadata-enrichment-live/' + $runName
$outputPath = Join-Path $projectRoot $relativeOutput
$secureToken = $null
$temporaryToken = $false
$runExit = 1

Push-Location -LiteralPath $projectRoot
try {
    if ($Collect -and [string]::IsNullOrWhiteSpace($env:TMDB_READ_ACCESS_TOKEN)) {
        $secureToken = Read-Host 'TMDB API Read Access Token (hidden; never paste in chat)' -AsSecureString
        if ($secureToken.Length -eq 0) { throw 'TMDB token is required; no collection started.' }
        $env:TMDB_READ_ACCESS_TOKEN = [System.Net.NetworkCredential]::new('', $secureToken).Password
        $temporaryToken = $true
    }
    # Fixed mode/path only; the token is inherited through the environment, never argv.
    # Merge native stderr inside cmd for Windows PowerShell 5.1 LASTEXITCODE handling.
    $PSNativeCommandUseErrorActionPreference = $false
    $collectorCommand = 'python -X utf8 scripts\metadata_enrichment_dry_run.py ' + $mode + ' --output "' + $relativeOutput + '" 2>&1'
    & $env:ComSpec /d /c $collectorCommand
    $runExit = $LASTEXITCODE
    New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
    [ordered]@{
        mode = $mode
        exitCode = $runExit
        output = $outputPath
        outputPresent = (Test-Path -LiteralPath (Join-Path $outputPath 'collection.json'))
        databaseApplied = $false
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputRoot 'launcher-last-run.json') -Encoding UTF8
    Write-Output ('Result folder: ' + $outputPath)
} finally {
    if ($temporaryToken) { Remove-Item Env:TMDB_READ_ACCESS_TOKEN -ErrorAction SilentlyContinue }
    if ($null -ne $secureToken) { $secureToken.Dispose() }
    Pop-Location
}
if ($runExit -ne 0) { throw 'Metadata run was blocked or incomplete. Keep the result folder; do not automatically retry.' }
