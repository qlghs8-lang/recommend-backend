import json
import tempfile
import unittest
from pathlib import Path
from prepare_quality_remaining import OUT, PINS, prepare, prior_reports, check_not_launched, run_ceiling


class RemainingTest(unittest.TestCase):
    def temporary(self):
        base = Path(__file__).resolve().parents[1] / OUT / 'test-temp'
        base.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=base)
        self.assertTrue(Path(temporary.name).resolve().is_relative_to(base.resolve()))
        return temporary

    def test_cumulative_and_lower_existing_caps_are_preserved(self):
        self.assertEqual(.30, run_ceiling(.6292521))
        self.assertEqual(.01, run_ceiling(.6292521, .01))
        self.assertAlmostEqual(.9292521, .6292521 + run_ceiling(.6292521))

    def test_seven_real_reports_are_pinned_and_accepted_schemas_included(self):
        root = Path(__file__).resolve().parents[1]
        self.assertAlmostEqual(.6292521, prior_reports(root))
        self.assertEqual(7, len([p for p in PINS if '/live-' in p or '/pilot-' in p]))

    def test_marker_blocks_without_reading_any_other_input(self):
        with self.temporary() as temporary:
            root = Path(temporary); out = root / OUT; out.mkdir(parents=True)
            (out / 'launch-started.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'already launched'):
                prepare(root)

    def test_prior_result_is_preserved_and_blocks_even_without_marker(self):
        with self.temporary() as temporary:
            root = Path(temporary); out = root / OUT; out.mkdir(parents=True)
            result = out / 'pilot-123.json'; result.write_text('{"original":true}')
            with self.assertRaisesRegex(ValueError, 'already launched'):
                prepare(root)
            self.assertEqual('{"original":true}', result.read_text())

    def test_peer_checkout_marker_prevents_duplicate_launch(self):
        with self.temporary() as temporary:
            root = Path(temporary); peer = root / 'peer'; (peer / OUT).mkdir(parents=True)
            (peer / OUT / 'launch-started.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'already launched'):
                check_not_launched(root, peer)

    def test_unreviewed_prior_run_is_detected_before_inputs_are_read(self):
        with self.temporary() as temporary:
            root = Path(temporary)
            for relative in PINS:
                if '/live-' in relative or '/pilot-' in relative:
                    p = root / relative; p.parent.mkdir(parents=True, exist_ok=True); p.write_text('{}')
            extra = root / 'target/quality-remeasurement-20261125/pilot-999.json'; extra.write_text('{}')
            with self.assertRaisesRegex(ValueError, 'Additional or missing prior run'):
                prior_reports(root)


if __name__ == '__main__':
    unittest.main()
