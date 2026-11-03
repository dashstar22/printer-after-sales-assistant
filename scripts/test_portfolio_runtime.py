"""No Docker or model calls; exercise destructive-upgrade and missing-key boundaries."""
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import portfolio_runtime as runtime


class PortfolioRuntimeSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def jar(self, name, migrations):
        path = self.folder / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("BOOT-INF/classes/local/printer/administration/AdministratorCommand.class", b"class-fixture")
            for filename, text in migrations.items():
                archive.writestr("BOOT-INF/classes/db/migration/" + filename, text)
        return path

    def test_upgrade_allows_only_unchanged_history_plus_new_migration(self):
        old = self.jar("old.jar", {"V1__base.sql": "create table t(id int);", "V15__external_call_budgets.sql": "budget"})
        new = self.jar("new.jar", {"V1__base.sql": "create table t(id int);", "V15__external_call_budgets.sql": "budget", "V16__new.sql": "new"})
        runtime.compatible_migrations(old, new)

    def test_missing_historical_migration_refused_before_stopping(self):
        old = self.jar("old.jar", {"V1__base.sql": "base", "V15__external_call_budgets.sql": "budget"})
        new = self.jar("new.jar", {"V15__external_call_budgets.sql": "budget"})
        with patch.object(runtime, "stop") as stop:
            with self.assertRaisesRegex(runtime.Failure, "MIGRATION_DOWNGRADE"):
                runtime.upgrade({"jar": str(old)}, new)
            stop.assert_not_called()
        self.assertEqual(runtime.migration_manifest(old)["BOOT-INF/classes/db/migration/V1__base.sql"], runtime.hashlib.sha256(b"base").hexdigest())

    def test_changed_historical_sql_refused(self):
        old = self.jar("old.jar", {"V1__base.sql": "base"})
        new = self.jar("new.jar", {"V1__base.sql": "modified"})
        with self.assertRaisesRegex(runtime.Failure, "MIGRATION_DOWNGRADE_OR_REWRITE"):
            runtime.compatible_migrations(old, new)

    def test_empty_key_fails_before_resource_creation(self):
        args = runtime.argparse.Namespace(config=None, mode="real")
        with patch.dict(os.environ, {}, clear=True), patch.object(runtime, "run") as run:
            with self.assertRaisesRegex(runtime.Failure, "REAL_AI_API_KEY_REQUIRED"):
                runtime.init(args)
            run.assert_not_called()

    def test_empty_migration_manifest_refused(self):
        empty = self.jar("empty.jar", {})
        with self.assertRaisesRegex(runtime.Failure, "MIGRATION_MANIFEST_INVALID"):
            runtime.migration_manifest(empty)


if __name__ == "__main__":
    unittest.main()
