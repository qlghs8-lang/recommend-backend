"""Offline regression for the public baseline assembler; no provider/DB calls."""
import copy
from pathlib import Path
import tempfile
import unittest
import consolidate_quality_baseline as c


class ConsolidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline, cls.packet = c.build(c.ROOT)

    def test_frozen_corpus_drift_rejected_before_analysis(self):
        with tempfile.TemporaryDirectory(dir=c.ROOT/'target') as directory:
            root=Path(directory);p=root/next(iter(c.PINS));p.parent.mkdir(parents=True);p.write_text('{}')
            with self.assertRaisesRegex(ValueError,'Frozen input changed'):
                c.check_pins(root)

    def test_duplicate_actual_turn_cannot_hide_missing_turn(self):
        specs=[dict(turnId='D01'),dict(turnId='D02')]
        with self.assertRaises(ValueError):
            c.validate_cases(specs,[dict(turnId='D01'),dict(turnId='D01')])

    def test_unknown_or_boolean_duration_never_satisfies_limit(self):
        for value in [None,False,True,0,101]:
            observation=c.hard_observation(dict(type='MOVIE',genres='',runtime_minutes=value),dict(movie_minutes_max=100))
            self.assertEqual(['movie_minutes_max'],observation['supportedFailures'])
        self.assertEqual([],c.hard_observation(dict(type='MOVIE',genres='',runtime_minutes=100),dict(movie_minutes_max=100))['supportedFailures'])

    def test_visible_plot_matches_java_utf16_substring(self):
        self.assertEqual(200,len(c.java_visible('😀'*201)))
        self.assertEqual(400,len(c.java_visible('a'*399+'😀').encode('utf-16-le',errors='surrogatepass'))//2)

    def test_failed_answers_and_denominators_not_relabelled_as_success(self):
        s=self.baseline['latestDevelopmentObservation']
        self.assertEqual((36,33,3,22),(s['attempted'],s['answered'],s['invalidResponseTurns'],s['returnedWorkOccurrences']))
        self.assertTrue(self.baseline['phase2Complete'])
        self.assertEqual('CODEX_PROVISIONAL_DEVELOPMENT_BASELINE_ONLY',self.baseline['phase2CompletionScope'])
        self.assertFalse(self.baseline['originalV1CriteriaSatisfied'])
        self.assertTrue(all(v=='UNJUDGED' for v in self.baseline['formalMetrics'].values()))
        self.assertEqual(0,self.baseline['validation']['attemptedTurns'])
        self.assertEqual('1.1838605',self.baseline['budget']['reservationEstimateUsd'])

    def test_review_packet_has_no_model_output_or_transferred_seed_labels(self):
        for case in self.packet['cases']:
            self.assertNotIn('selectedIds',case)
            self.assertNotIn('answer',case)
            self.assertLessEqual(case['poolSize'],36)
            for candidate in case['candidates']:
                self.assertNotIn('sources',candidate)
                self.assertIsNone(candidate['relevance'])
                self.assertIsNone(candidate['rationale'])
        self.assertTrue(self.packet['postRunPoolNotPreOutputGold'])

    def test_every_returned_work_is_in_review_pool_even_if_discovery_missed_it(self):
        pools={case['turnId']:{p['content']['id'] for p in case['candidates']} for case in self.packet['cases']}
        for case in self.baseline['cases']:
            self.assertTrue(set(case['selectedIds'])<=pools[case['turnId']])

    def test_discovery_deterministic_and_independent_of_actual_output(self):
        rows=[dict(id=i,type='MOVIE',title='a',overview='b',genres='') for i in range(1,40)]
        a=c.independent_discovery(rows,[],[],dict(hard={}), 'test')
        b=c.independent_discovery(list(reversed(rows)),[],[],dict(hard={}), 'test')
        self.assertEqual(a,b)
        self.assertEqual(24,len(a))

    def test_catalog_instructions_are_escaped_as_data_in_html(self):
        packet=copy.deepcopy(self.packet)
        packet['cases']=packet['cases'][:1]
        packet['cases'][0]['candidates']=packet['cases'][0]['candidates'][:1]
        work=packet['cases'][0]['candidates'][0]['content']
        work['overview']='<script>alert("untrusted")</script>'
        text=c.packet_html(packet)
        self.assertNotIn('<script>',text)
        self.assertIn('&lt;script&gt;',text)


if __name__=='__main__':
    unittest.main()
