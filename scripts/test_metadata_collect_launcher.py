"""Private-terminal launcher contracts with a fake python.cmd, no DB/API/key access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class MetadataCollectLauncherTest(unittest.TestCase):
    def setUp(self):
        self.fixture_root = (ROOT / 'target/metadata-collect-launcher-fixtures').resolve()
        self.fixture_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='metadata launcher ', dir=self.fixture_root)
        def cleanup():
            target = Path(self.temp.name).resolve()
            if target.parent != self.fixture_root or not target.name.startswith('metadata launcher '):
                raise RuntimeError('Unsafe fixture cleanup path')
            self.temp.cleanup()
        self.addCleanup(cleanup)
        self.root = Path(self.temp.name) / 'fixture & project'
        (self.root / 'scripts').mkdir(parents=True)
        shutil.copy2(ROOT / 'scripts/run_metadata_enrichment_collect.ps1', self.root / 'scripts')
        (self.root / 'python.cmd').write_text(
            '@echo off\necho synthetic-collector-stdout\necho synthetic-warning 1>&2\n'
            'echo %* > args.txt\n'
            'if "%TMDB_READ_ACCESS_TOKEN%"=="synthetic-private-token" (echo token-present) else (echo token-absent)\n'
            'exit /b %METADATA_LAUNCHER_TEST_EXIT%\n', encoding='ascii')
        self.shells = [p for p in (shutil.which('powershell.exe'), shutil.which('pwsh.exe')) if p]
        self.assertTrue(self.shells)

    def invoke(self, shell, code=0, collect=False, inherited=False, empty=False):
        env = {k:v for k,v in os.environ.items() if k.upper() not in ('TMDB_READ_ACCESS_TOKEN', 'PSMODULEPATH')}
        env['METADATA_LAUNCHER_TEST_EXIT'] = str(code)
        if inherited: env['TMDB_READ_ACCESS_TOKEN'] = 'synthetic-private-token'
        driver = self.root / 'driver.ps1'
        driver.write_text('''$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$originalLocation = (Get-Location).Path
$promptState = @{ Prompted = $false }
function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    $promptState.Prompted = $true
    if (-not $AsSecureString) { throw 'Token input must be hidden' }
    if (EMPTY) { return [System.Security.SecureString]::new() }
    return ConvertTo-SecureString 'synthetic-private-token' -AsPlainText -Force
}
$failed = $false
try { & (Join-Path $PSScriptRoot 'scripts/run_metadata_enrichment_collect.ps1') MODE }
catch { $failed = $true; 'stub-driver-error' }
if ((Get-Location).Path -ne $originalLocation) { throw 'Working directory was not restored' }
if ($PSNativeCommandUseErrorActionPreference -ne $true -or $ErrorActionPreference -ne 'Stop') { throw 'Caller preferences changed' }
if (INHERITED) {
    if ($env:TMDB_READ_ACCESS_TOKEN -ne 'synthetic-private-token') { throw 'Inherited token changed' }
} elseif (Test-Path Env:TMDB_READ_ACCESS_TOKEN) { throw 'Temporary token was not cleared' }
if ($promptState.Prompted -ne PROMPTED) { throw 'Unexpected prompt behavior' }
'cleanup-verified'
if ($failed) { exit 1 }
'''.replace('EMPTY', '$true' if empty else '$false')
            .replace('MODE', '-Collect' if collect else '')
            .replace('INHERITED', '$true' if inherited else '$false')
            .replace('PROMPTED', '$true' if collect and not inherited else '$false'), encoding='utf-8-sig')
        result = subprocess.run([shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(driver)],
                                cwd=self.root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        output = result.stdout.decode(errors='replace')
        self.assertIn('cleanup-verified', output)
        self.assertNotIn('synthetic-private-token', output)
        report_path = self.root / 'target/metadata-enrichment-live/launcher-last-run.json'
        report = json.loads(report_path.read_text(encoding='utf-8-sig')) if report_path.exists() else None
        return result, report, output

    def test_default_prepare_never_prompts_and_records_native_exit(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                result, report, output = self.invoke(shell)
                self.assertEqual(0, result.returncode); self.assertEqual('prepare', report['mode'])
                self.assertEqual(0, report['exitCode']); self.assertFalse(report['databaseApplied'])
                self.assertIn('token-absent', output)

    def test_hidden_collect_cleans_temporary_token_on_success_and_failure(self):
        for shell in self.shells:
            for code in (0, 2, 7):
                with self.subTest(shell=shell, code=code):
                    result, report, output = self.invoke(shell, code, collect=True)
                    self.assertEqual(code != 0, result.returncode != 0)
                    self.assertEqual(code, report['exitCode']); self.assertEqual('collect', report['mode'])
                    self.assertIn('token-present', output)
                    args = (self.root / 'args.txt').read_text()
                    self.assertIn('metadata_enrichment_dry_run.py collect --output', args)
                    self.assertNotIn('synthetic-private-token', args)

    def test_inherited_token_is_preserved_without_prompting(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                result, report, output = self.invoke(shell, collect=True, inherited=True)
                self.assertEqual(0, result.returncode); self.assertIn('token-present', output)
                self.assertEqual(0, report['exitCode'])

    def test_empty_hidden_input_never_starts_collector(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                result, report, _ = self.invoke(shell, collect=True, empty=True)
                self.assertNotEqual(0, result.returncode); self.assertIsNone(report)
                self.assertFalse((self.root / 'args.txt').exists())


if __name__ == '__main__':
    unittest.main()
