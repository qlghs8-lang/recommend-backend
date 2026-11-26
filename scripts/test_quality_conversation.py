import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import prepare_quality_conversation as subject

TEMP = Path(__file__).resolve().parents[1]/subject.OUT/'python-temp'
TEMP.mkdir(parents=True, exist_ok=True)


class ConversationPreparationTests(unittest.TestCase):
    def test_lower_and_cumulative_caps(self):
        self.assertEqual(.18, subject.run_ceiling(.8080466))
        self.assertEqual(.01, subject.run_ceiling(.8080466, .01))
        self.assertAlmostEqual(.001, subject.run_ceiling(.999))
        for bad in (0, -.1, float('nan'), float('inf'), 1.1):
            with self.assertRaises(ValueError):
                subject.run_ceiling(.8080466, bad)

    def test_current_eight_report_ledger(self):
        root = Path(__file__).resolve().parents[1]
        self.assertEqual(8, len(subject.REPORTS))
        self.assertEqual(.8080466, subject.prior_reports(root))

    def test_any_own_or_peer_marker_blocks(self):
        with tempfile.TemporaryDirectory(dir=TEMP) as tmp:
            root, peer = Path(tmp)/'one', Path(tmp)/'two'
            folder = peer/subject.OUT
            folder.mkdir(parents=True)
            (folder/'launch-started.json').write_text('{}')
            for first, second in ((root, peer), (peer, root)):
                with self.assertRaises(ValueError): subject.check_not_launched(first, second)

    def test_existing_result_is_preserved(self):
        with tempfile.TemporaryDirectory(dir=TEMP) as tmp:
            root = Path(tmp); folder = root/subject.OUT; folder.mkdir(parents=True)
            report = folder/'pilot-123.json'; report.write_text('{"original":true}')
            with self.assertRaises(ValueError): subject.prepare(root)
            self.assertEqual('{"original":true}', report.read_text())

    def test_changed_prior_scope_rejected_before_writing_plan(self):
        with tempfile.TemporaryDirectory(dir=TEMP) as tmp:
            root = Path(tmp)
            with self.assertRaises(ValueError): subject.prior_reports(root)
            self.assertFalse((root/subject.OUT/'run-plan.json').exists())

    def test_prepared_scope_and_persistence_are_fixed(self):
        with tempfile.TemporaryDirectory(dir=TEMP) as tmp, patch.object(subject, 'prior_reports', return_value=.8080466), patch.object(subject, 'sha', return_value='a'*64):
            root = Path(tmp)
            plan = subject.prepare(root, .05)
            self.assertEqual(['DC01', 'DC02', 'DC03'], plan['selectedCaseIds'])
            self.assertEqual(12, plan['selectedTurns'])
            self.assertEqual(.05, plan['runMaxEstimatedUsd'])
            self.assertEqual(.8580466, plan['cumulativeMaxEstimatedUsd'])
            self.assertEqual('ISOLATED_H2_ACTUAL_HISTORY_SERVICE', plan['persistence'])
            self.assertEqual(plan, json.loads((root/subject.OUT/'run-plan.json').read_text()))


if __name__ == '__main__':
    unittest.main()
