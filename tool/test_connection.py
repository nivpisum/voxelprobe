"""Paired descriptor migration keeps instance binding and fails closed."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import mc


class ConnectionMigrationTest(unittest.TestCase):
    def test_canonical_precedence_and_legacy_fallback(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, MINECRAFT_GAME_DIR=temporary):
            directory = Path(temporary).resolve()
            config = directory/'config'
            config.mkdir()
            legacy = config/'debugbridge-connection.json'
            canonical = config/'voxel_probe_connection.json'
            info = {'protocol_version':2, 'port':9881, 'token':'legacy_private_token',
                    'instance_id':'old', 'game_dir':str(directory)}
            legacy.write_text(json.dumps(info), encoding='utf-8')
            self.assertEqual(mc.connection(mc.DEFAULT_PORT)['instance_id'], 'old')
            info.update(port=9883, token='current_private_token', instance_id='new')
            canonical.write_text(json.dumps(info), encoding='utf-8')
            result = mc.connection(mc.DEFAULT_PORT)
            self.assertEqual((result['port'], result['instance_id']), (9883, 'new'))
            self.assertEqual(result['headers'], {'Authorization':'Bearer current_private_token'})
            canonical.write_text('{invalid}', encoding='utf-8')
            with self.assertRaises(RuntimeError):
                mc.connection(mc.DEFAULT_PORT)

    def test_canonical_descriptor_must_match_the_game_directory(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, MINECRAFT_GAME_DIR=temporary):
            config = Path(temporary)/'config'
            config.mkdir()
            info = {'protocol_version':2, 'port':9883, 'token':'private',
                    'instance_id':'new', 'game_dir':str(Path(temporary)/'another_instance')}
            (config/'voxel_probe_connection.json').write_text(json.dumps(info), encoding='utf-8')
            with self.assertRaises(RuntimeError):
                mc.connection(mc.DEFAULT_PORT)


if __name__ == '__main__':
    unittest.main()
