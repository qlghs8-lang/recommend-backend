"""Cross-shell launcher regression. Only a fake mvnw.cmd is run; no Java, DB or API."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RuntimeModelLauncherTest(unittest.TestCase):
    def setUp(self):
        fixture_root = (ROOT / "target/runtime-launcher-fixtures").resolve()
        fixture_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="runtime launcher ", dir=fixture_root)
        def cleanup():
            target = Path(self.temp.name).resolve()
            if target.parent != fixture_root or not target.name.startswith("runtime launcher "):
                raise RuntimeError("Unsafe fixture cleanup path")
            self.temp.cleanup()
        self.addCleanup(cleanup)
        self.root = Path(self.temp.name) / "fixture & project"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "src/test/resources/chat").mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/run_runtime_model_acceptance.ps1", self.root / "scripts")
        shutil.copy2(ROOT / "src/test/resources/chat/runtime-model-v1.json", self.root / "src/test/resources/chat")
        # Same harmless warning channel as the JVM; an arbitrary nonzero exit must still fail.
        (self.root / "mvnw.cmd").write_text(
            "@echo off\necho synthetic-maven-stdout\necho synthetic-jvm-warning 1>&2\n"
            "exit /b %RUNTIME_LAUNCHER_TEST_EXIT%\n", encoding="ascii")
        self.shells = [p for p in [shutil.which("powershell.exe"), shutil.which("pwsh.exe")] if p]
        self.assertTrue(self.shells, "A Windows PowerShell host is required")

    def invoke(self, shell, code, live=False):
        env = os.environ.copy()
        # Never inherit real evaluation secrets into the stub process.
        for key in list(env):
            if key.upper() in {"CHAT_EVAL_OPENAI_API_KEY", "CHAT_EVAL_MODEL", "PSMODULEPATH"}:
                env.pop(key)
        # A pwsh parent can export only its own module locations; let each host
        # build its native defaults instead of hiding Windows PowerShell cmdlets.
        env["RUNTIME_LAUNCHER_TEST_EXIT"] = str(code)
        driver = self.root / "driver.ps1"
        driver.write_text("""$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$originalLocation = (Get-Location).Path
function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    if ($AsSecureString) { return ConvertTo-SecureString 'synthetic-not-a-real-key' -AsPlainText -Force }
    return 'synthetic-model'
}
$failed = $false
try { & (Join-Path $PSScriptRoot 'scripts/run_runtime_model_acceptance.ps1') MODE }
catch { $failed = $true; 'stub-driver-error: ' + $_.Exception.Message; $_.ScriptStackTrace }
if (Test-Path Env:CHAT_EVAL_OPENAI_API_KEY) { throw 'Temporary key leaked after invocation' }
if (Test-Path Env:CHAT_EVAL_MODEL) { throw 'Temporary model leaked after invocation' }
if ((Get-Location).Path -ne $originalLocation) { throw 'Working directory was not restored' }
if ($ErrorActionPreference -ne 'Stop') { throw 'Caller preference changed' }
if ($PSNativeCommandUseErrorActionPreference -ne $true) { throw 'Caller native preference changed' }
'cleanup-verified'
if ($failed) { exit 1 }
""".replace("MODE", "-Run" if live else "-Rehearse"), encoding="utf-8-sig")
        result = subprocess.run([shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                                 "-File", str(driver)], env=env, cwd=self.root,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        output = result.stdout.decode(errors="replace")
        report_path = self.root / "target/runtime-model-acceptance/launcher-last-run.json"
        self.assertTrue(report_path.exists(), output)
        report = json.loads(report_path.read_text(encoding="utf-8-sig"))
        self.assertTrue(Path(report["log"]).exists(), output)
        log_bytes = Path(report["log"]).read_bytes()
        log = log_bytes.decode("utf-16" if log_bytes.startswith(b"\xff\xfe") else "utf-8-sig")
        self.assertIn("synthetic-maven-stdout", log)
        self.assertIn("synthetic-jvm-warning", log)
        self.assertIn("cleanup-verified", output)
        self.assertEqual(code, report["exitCode"])
        return result, report

    def test_warning_is_logged_without_aborting_successful_rehearsal(self):
        for shell in self.shells:
            with self.subTest(shell=Path(shell).name):
                result, report = self.invoke(shell, 0)
                self.assertEqual(0, result.returncode)
                self.assertFalse(report["qualityAccepted"])

    def test_nonzero_exit_is_reported_and_temporary_live_credentials_are_cleared(self):
        for shell in self.shells:
            with self.subTest(shell=Path(shell).name):
                result, report = self.invoke(shell, 3, live=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(report["qualityAccepted"])

    def test_successful_stub_live_run_still_cleans_temporary_credentials(self):
        for shell in self.shells:
            with self.subTest(shell=Path(shell).name):
                result, report = self.invoke(shell, 0, live=True)
                self.assertEqual(0, result.returncode)
                self.assertTrue(report["qualityAccepted"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
