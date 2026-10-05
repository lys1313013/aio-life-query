import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('build_metadata', Path(__file__).resolve().parents[1] / 'scripts/build_metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class MetadataSecurityTest(unittest.TestCase):
    def test_secrets_remain_private_until_complete_atomic_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'metadata'
            output.mkdir(mode=0o755)
            destination = output / 'auth_config.json'
            destination.write_text('{"old":true}\n')
            original_replace = os.replace
            observations = []

            def check_written_file(descriptor):
                self.assertEqual(0o600, stat.S_IMODE(os.fstat(descriptor).st_mode))
                self.assertEqual({'old': True}, json.loads(destination.read_text()))
                observations.append('private-write')

            def check_replacement(source, target):
                self.assertEqual(0o640, stat.S_IMODE(Path(source).stat().st_mode))
                self.assertEqual({'secret': 'fixture-webhook-key'}, json.loads(Path(source).read_text()))
                observations.append('complete-replacement')
                original_replace(source, target)

            old_umask = os.umask(0o022)
            try:
                with patch.object(metadata.os, 'fsync', side_effect=check_written_file), patch.object(metadata.os, 'replace', side_effect=check_replacement):
                    metadata.write_bundle(output, [('auth_config.json', {'secret': 'fixture-webhook-key'})])
            finally:
                os.umask(old_umask)
            self.assertEqual(['private-write', 'complete-replacement'], observations)
            self.assertEqual(0o750, stat.S_IMODE(output.stat().st_mode))
            self.assertEqual(0o640, stat.S_IMODE(destination.stat().st_mode))
            self.assertEqual({'secret': 'fixture-webhook-key'}, json.loads(destination.read_text()))
            self.assertEqual([destination], list(output.iterdir()))

    def test_failed_write_preserves_existing_bundle_and_removes_temporary_secret(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            destination = output / 'auth_config.json'
            destination.write_text('old-content')
            with patch.object(metadata.os, 'fsync', side_effect=OSError('fixture disk failure')):
                with self.assertRaises(OSError):
                    metadata.write_bundle(output, [('auth_config.json', {'secret': 'fixture'})])
            self.assertEqual('old-content', destination.read_text())
            self.assertEqual([destination], list(output.iterdir()))

    def test_replaces_destination_symlink_without_overwriting_its_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / 'metadata'
            output.mkdir()
            target = root / 'outside.json'
            target.write_text('untouched')
            destination = output / 'auth_config.json'
            destination.symlink_to(target)
            metadata.write_bundle(output, [('auth_config.json', {'secret': 'fixture'})])
            self.assertFalse(destination.is_symlink())
            self.assertEqual('untouched', target.read_text())
            self.assertEqual({'secret': 'fixture'}, json.loads(destination.read_text()))

    def test_refuses_symbolic_link_output_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / 'target'
            target.mkdir()
            original_mode = target.stat().st_mode
            output = root / 'metadata'
            output.symlink_to(target, target_is_directory=True)
            with self.assertRaises(ValueError):
                metadata.write_bundle(output, [('auth_config.json', {'secret': 'fixture'})])
            self.assertEqual(original_mode, target.stat().st_mode)
            self.assertEqual([], list(target.iterdir()))


if __name__ == '__main__':
    unittest.main()
