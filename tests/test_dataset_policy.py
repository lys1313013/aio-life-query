import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('build_metadata', Path(__file__).resolve().parents[1] / 'scripts/build_metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class DatasetPolicyTest(unittest.TestCase):
    def test_six_public_datasets_and_exact_field_grants(self):
        bundle, schema = metadata.build('http://fixture', 'fixture-key')
        expected = {'time_record', 'exercise_record', 'read_record', 'movie', 'b_video', 'performance', 'income_month'}
        self.assertEqual(expected | {'time_record_category', 'exercise_record_type', 'income_month_type'}, {table['name'] for table in schema['collections']})
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
                self.assertIn('userId', permission['permissions'][0]['output']['allowedFields'])
                self.assertIn('isDeleted', permission['permissions'][0]['output']['allowedFields'])
                columns = [field['column'] for field in dataset['fields'].values()]
                if dataset.get('nativeSql'):
                    self.assertIn(dataset['name'], metadata.connector_config()['nativeQueries'])
                    continue
                self.assertEqual(set(columns), {column['name'] for column in tables[dataset['name']]['columns']})
                self.assertIn('GRANT SELECT ('+', '.join(columns)+')\n  ON public.'+dataset['name'], grants)
                self.assertEqual({}, tables[dataset['name']]['fks'])
        self.assertIn('order_number', schema['object_types']['performance']['fields'])
        self.assertIn('seat_info', schema['object_types']['performance']['fields'])
        self.assertIn('notes', schema['object_types']['b_video']['fields'])
        self.assertEqual({'type': 'nullable', 'underlying_type': {'type': 'named', 'name': 'JSON'}},
                         schema['object_types']['b_video']['fields']['pages_info']['type'])

    def test_category_is_only_a_scoped_relationship_with_no_root_query(self):
        bundle, _ = metadata.build('http://fixture', 'fixture-key')
        definitions = bundle['subgraphs'][0]['objects']
        category = next(item['definition'] for item in definitions if item['kind'] == 'Model' and item['definition']['name'] == 'TimeCategoryModel')
        self.assertNotIn('graphql', category)
        permission = next(item['definition'] for item in definitions if item['kind'] == 'ModelPermissions' and item['definition']['modelName'] == 'TimeCategoryModel')
        self.assertEqual('ai_time_reader', permission['permissions'][0]['role'])
        self.assertEqual({'sessionVariable': 'x-hasura-user-id'}, permission['permissions'][0]['select']['filter']['fieldComparison']['value'])
        output = next(item['definition'] for item in definitions if item['kind'] == 'TypePermissions' and item['definition']['typeName'] == 'TimeCategory')
        self.assertEqual({'id', 'name', 'recordId'}, set(output['permissions'][0]['output']['allowedFields']))
        native = metadata.connector_config()['nativeQueries']['time_record_category']
        self.assertEqual({}, native['arguments'])
        self.assertIn('o.user_id = t.user_id', native['sql'])
        self.assertIn('c.user_id = 0 OR c.user_id = t.user_id', native['sql'])

    def test_type_relationships_are_scoped_and_have_no_public_roots(self):
        bundle, _ = metadata.build('http://fixture', 'fixture-key')
        objects = bundle['subgraphs'][0]['objects']
        for dataset, target, collection in [('exercise_record', 'ExerciseType', 'exercise_record_type'), ('income_month', 'IncomeType', 'income_month_type')]:
            with self.subTest(dataset=dataset):
                role = next(d['role'] for d in metadata.DATASETS if d['name'] == dataset)
                model = next(o['definition'] for o in objects if o['kind'] == 'Model' and o['definition']['name'] == target+'Model')
                self.assertNotIn('graphql', model)
                permissions = next(o['definition'] for o in objects if o['kind'] == 'ModelPermissions' and o['definition']['modelName'] == target+'Model')['permissions']
                self.assertEqual([role], [p['role'] for p in permissions])
                self.assertEqual({'sessionVariable': 'x-hasura-user-id'}, permissions[0]['select']['filter']['fieldComparison']['value'])
                sql = metadata.connector_config()['nativeQueries'][collection]['sql']
                self.assertIn('d.user_id = 0 OR d.user_id = r.user_id', sql)
                self.assertIn('o.user_id = r.user_id', sql)
                self.assertIn('d.template_id IS NULL AND d.is_deleted = 0', sql)
                self.assertIn("COALESCE(d.is_readonly, 'N') <> 'Y'", sql)
