import math
import tempfile
import unittest
from pathlib import Path
from prepare_quality_remeasurement import ceiling, prepare, OUT


class RemeasurementTest(unittest.TestCase):
    def temporary(self):
        base = Path(__file__).resolve().parents[1] / OUT / 'test-temp'
        base.mkdir(parents=True, exist_ok=True)
        return tempfile.TemporaryDirectory(dir=base)

    def test_prior_reservations_and_lower_user_cap_are_preserved(self):
        self.assertEqual(.40, ceiling(.1823251))
        self.assertEqual(.05, ceiling(.1823251, .05))
        self.assertAlmostEqual(.1, ceiling(.9))
        for prior in (-1, 1, math.nan, math.inf):
            with self.assertRaises(ValueError):
                ceiling(prior)
        for existing in (0, -1, 2, math.nan, math.inf):
            with self.assertRaises(ValueError):
                ceiling(.1823251, existing)

    def test_previous_launch_blocks_even_when_no_provider_report_was_written(self):
        with self.temporary() as temporary:
            root = Path(temporary)
            (root / OUT).mkdir(parents=True)
            (root / OUT / 'launch-started.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'already launched'):
                prepare(root)

    def test_existing_result_is_not_overwritten_or_automatically_rerun(self):
        with self.temporary() as temporary:
            root = Path(temporary)
            (root / OUT).mkdir(parents=True)
            report = root / OUT / 'pilot-123.json'
            report.write_text('{"original":true}')
            with self.assertRaisesRegex(ValueError, 'already launched'):
                prepare(root)
            self.assertEqual('{"original":true}', report.read_text())


if __name__ == '__main__':
    unittest.main()
