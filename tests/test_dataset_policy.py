import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('build_metadata', Path(__file__).resolve().parents[1] / 'scripts/build_metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class DatasetPolicyTest(unittest.TestCase):
    def test_only_six_published_collections_and_exact_field_grants(self):
        bundle, schema = metadata.build('http://fixture', 'fixture-key')
        expected = {'time_record', 'exercise_record', 'read_record', 'movie', 'b_video', 'performance'}
        self.assertEqual(expected, {table['name'] for table in schema['collections']})
        objects = bundle['subgraphs'][0]['objects']
        by_kind = lambda kind: [item['definition'] for item in objects if item['kind'] == kind]
        tables = {table['tableName']: table for table in metadata.connector_config()['tables']}
        grants = (Path(__file__).resolve().parents[1] / 'deploy/readonly-account.sql.example').read_text()
        for dataset in metadata.DATASETS:
            with self.subTest(dataset=dataset['name']):
                model = next(item for item in by_kind('ModelPermissions') if item['modelName'] == dataset['typeName']+'Model')
                self.assertEqual([dataset['role']], [item['role'] for item in model['permissions']])
                filters = model['permissions'][0]['select']['filter']['and']
                self.assertEqual({'sessionVariable': 'x-hasura-user-id'}, filters[0]['fieldComparison']['value'])
                self.assertEqual({'literal': 0}, filters[1]['fieldComparison']['value'])
                permission = next(item for item in by_kind('TypePermissions') if item['typeName'] == dataset['typeName'])
                self.assertEqual(set(dataset['fields']), set(permission['permissions'][0]['output']['allowedFields']))
                self.assertNotIn('userId', permission['permissions'][0]['output']['allowedFields'])
                columns = [field['column'] for field in dataset['fields'].values()] + ['user_id', 'is_deleted']
                self.assertEqual(set(columns), {column['name'] for column in tables[dataset['name']]['columns']})
                self.assertIn('GRANT SELECT ('+', '.join(columns)+')\n  ON aio_life.'+dataset['name'], grants)
                self.assertEqual({}, tables[dataset['name']]['fks'])
        self.assertNotIn('order_number', schema['object_types']['performance']['fields'])
        self.assertNotIn('seat_info', schema['object_types']['performance']['fields'])
        self.assertNotIn('notes', schema['object_types']['b_video']['fields'])
