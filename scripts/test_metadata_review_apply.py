"""Pinned review-apply offline integrity tests; synthetic decisions are not quality scores."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import apply_metadata_review_release as app
import metadata_review_release as review
from catalog_pool import CatalogError, digest, write_json
from test_metadata_draft_apply import write_input
from test_metadata_review_release import approved, patch_context, review_fixture


def setup_fixture(test):
    bundle, contract = review_fixture()
    ctx = patch_context(test, copy.deepcopy(bundle), contract)
    parent = review.compile_review(ctx, review.template(ctx))
    records = [approved(ctx, k) for k in ('KEYWORD', 'ALIAS', 'RUNTIME')]
    values = review.template(ctx) | {'revision': 'review-002', 'parent_version_sha': parent['version']['version_sha'], 'records': records}
    compiled = review.compile_review(ctx, values)
    pins = {'decisions': digest(values), 'parent': parent['version']['version_sha'], 'version': compiled['version']['version_sha'],
            'release': compiled['candidate']['release_sha'], 'selected': 3}
    p = patch.object(app, 'PINS', pins); p.start(); test.addCleanup(p.stop)
    folder = app.ROOT/'target/metadata-review-apply-test-tmp'; folder.mkdir(parents=True, exist_ok=True)
    temp = tempfile.TemporaryDirectory(dir=folder); test.addCleanup(temp.cleanup)
    test.folder = Path(temp.name)
    write_input(test.folder/'input', ctx['bundle'])
    test.collection = test.folder/'input/collection.json'
    test.decisions = test.folder/'decisions.json'; write_json(test.decisions, values)
    field = test.folder/'field-plan.json'; write_json(field, ctx['plan'])
    p = patch.object(review, 'REVIEW_PLAN', field); p.start(); test.addCleanup(p.stop)
    # Python default args are bound at definition; specify the current pinned plan.
    original = app.compile_fixed
    p = patch.object(app, 'compile_fixed', side_effect=lambda c, d, f=None: original(c, d, f or field))
    p.start(); test.addCleanup(p.stop)
    test.context, test.compiled, test.expected = app.compile_fixed(test.collection, test.decisions)


class MetadataReviewApplyTest(unittest.TestCase):
    def setUp(self): setup_fixture(self)

    def test_pinned_candidate_and_parent_rows(self):
        self.assertEqual([2, len(self.context['rows'])+3, 1], list(map(len, self.expected)))
        self.assertEqual(self.expected[0][0]['version_sha'], self.expected[0][1]['parent_sha'])
        self.assertEqual(0, self.expected[2][0]['active'])
        self.assertFalse(self.expected[2][0]['payload']['application_apply_supported'])

    def test_changed_decisions_are_rejected(self):
        values = app.stage.collect.load_json(self.decisions); values['revision'] = 'other'
        write_json(self.decisions, values)
        with self.assertRaises(CatalogError): app.compile_fixed(self.collection, self.decisions)

    def test_partial_or_foreign_history_rejected(self):
        for rows in [[self.expected[0][:1], [], []], [self.expected[0], [], self.expected[2]]]:
            with self.assertRaises(CatalogError): app.status_of(self.context, self.expected, {'rows': rows})

    def test_only_complete_empty_migration_or_full_identical_replay_allowed(self):
        prepared = {'raw': {'pin': 1}, 'reviews': {'schema_present': False, 'rows': [[], [], []]}}
        migrated = copy.deepcopy(prepared); migrated['reviews']['schema_present'] = True
        app.allowed_transition(self.context, self.expected, prepared, migrated)
        full = copy.deepcopy(migrated); full['reviews']['rows'] = self.expected
        app.allowed_transition(self.context, self.expected, prepared, full)
        full['raw']['pin'] = 2
        with self.assertRaises(CatalogError): app.allowed_transition(self.context, self.expected, prepared, full)

    def test_invalid_plan_does_not_initialize_connection(self):
        with patch.object(app, 'LocalCatalog') as db:
            self.assertEqual(1, app.main(['apply', '--plan', str(self.folder/'missing'), '--plan-sha256', '0'*64, '--output', str(self.folder/'out')]))
            db.assert_not_called()

    def test_schema_guard_enforces_inactive_checks_fk_defaults_and_no_triggers(self):
        sql = app.schema_guard()
        for text in ['CHECK_CLAUSE', "ENFORCED='YES'", 'active=0', 'COLUMN_DEFAULT IS NULL', 'REFERENCED_TABLE_SCHEMA=DATABASE()', 'TRIGGERS']:
            self.assertIn(text, sql)


if __name__ == '__main__': unittest.main()
