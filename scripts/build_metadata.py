#!/usr/bin/env python3
"""Build only explicitly published dataset metadata; never introspect/import every table."""
import argparse
import json
import os
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATASETS = json.loads((ROOT / 'src/main/resources/datasets.json').read_text())
RELATIONSHIPS = json.loads((ROOT / 'src/main/resources/relationships.json').read_text())

def fields(dataset):
    # 全部物理字段均可读；ModelPermissions 独立强制用户隔离和逻辑删除过滤。
    return dict(dataset['fields'])

def ndc_type(field):
    return {'BIGINT':'INT64', 'VARCHAR':'STRING', 'TEXT':'STRING', 'JSON':'JSON', 'DATE':'DATE', 'DATETIME':'TIMESTAMP',
            'INT':'INT32', 'TINYINT':'INT8', 'SMALLINT UNSIGNED':'INT32', 'DECIMAL':'BIGDECIMAL'}[field['sqlType']]

def api_type(field):
    return {'INT64':'BigInt', 'STRING':'String', 'DATE':'String', 'TIMESTAMP':'String',
            'INT32':'Int', 'INT8':'Int', 'BIGDECIMAL':'BigDecimal', 'JSON':'Json'}[ndc_type(field)]

def comparison(field):
    return {'INT64':'CategoryComparison', 'STRING':'IdComparison', 'DATE':'DateComparison',
            'TIMESTAMP':'TimestampComparison', 'INT32':'IntComparison', 'INT8':'TinyIntComparison',
            'BIGDECIMAL':'DecimalComparison'}[ndc_type(field)]

def obj(kind, definition):
    return {'kind': kind, 'version': 'v1', 'definition': definition}

def write_bundle(output, bundle):
    # 容器通过受控附加组读取；其他用户不可遍历，密钥从创建时就不可公开读取。
    if output.is_symlink():
        raise ValueError('Metadata directory must not be a symbolic link')
    output.mkdir(mode=0o750, parents=True, exist_ok=True)
    output.chmod(0o750)
    for name, value in bundle:
        descriptor, temporary = tempfile.mkstemp(prefix='.' + name + '-', dir=output)
        try:
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
                stream.flush()
                os.fsync(stream.fileno())
                os.fchmod(stream.fileno(), 0o640)
            os.replace(temporary, output / name)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

def connector_config():
    tables = []
    for dataset in DATASETS:
        columns = []
        for name, field in fields(dataset).items():
            columns.append({'name':field['column'], 'description':None, 'type':field['sqlType'],
                'numeric_precision':10 if field['kind']=='DECIMAL' else None,
                'numeric_scale':2 if field['kind']=='DECIMAL' else None,
                'nullable':field['nullable'], 'auto_increment':False, 'is_primarykey':name=='id'})
        tables.append({'tableName':dataset['name'], 'tableType':'TABLE', 'description':None,
                       'columns':columns, 'pks':['id'], 'fks':{}})
    return {'jdbcUrl':{'variable':'JDBC_URL'}, 'jdbcProperties':{'connectTimeout':'2000','socketTimeout':'6000',
            'sessionVariables':'max_execution_time=5000,group_concat_max_len=1000000', 'allowMultiQueries':'false', 'allowLoadLocalInfile':'false'},
            'tables':tables, 'functions':[], 'nativeQueries':{
                relation['collection']: {
                    'sql': (ROOT / relation['nativeSql']).read_text(),
                    'columns': {field['column']: {'type': 'nullable', 'underlying_type': {'type': 'named', 'name': field['sqlType']}}
                                if field['nullable'] else {'type': 'named', 'name': field['sqlType']}
                                for field in relation['fields'].values()},
                    'arguments': {}, 'isProcedure': False
                } for relations in RELATIONSHIPS.values() for relation in relations.values()}}

def build(connector_url, connector_key):
    scalar_types = {}
    for name, representation in [('STRING','string'), ('DATE','date'), ('TIMESTAMP','timestamp'),
                                 ('INT8','int8'), ('INT32','int32'), ('INT64','int64'), ('BIGDECIMAL','bigdecimal'), ('JSON','json')]:
        ops = {'_eq': {'type':'equal'}}
        if name not in ['STRING', 'JSON']:
            ops.update({op:{'type':'custom','argument_type':{'type':'named','name':name}} for op in ['_gte','_lt','_lte']})
        scalar_types[name] = {'representation':{'type':representation}, 'comparison_operators':ops, 'aggregate_functions':{}}
    for name, result in [('INT32','INT64'), ('BIGDECIMAL','BIGDECIMAL')]:
        scalar_types[name]['aggregate_functions']['sum'] = {'result_type':{'type':'named','name':result}}
    schema = {'scalar_types':scalar_types, 'object_types':{}, 'collections':[], 'functions':[], 'procedures':[]}
    for dataset in DATASETS:
        ndc_fields = {}
        for field in fields(dataset).values():
            kind = {'type':'named','name':ndc_type(field)}
            if field['nullable']: kind = {'type':'nullable','underlying_type':kind}
            ndc_fields[field['column']] = {'type':kind,'arguments':{}}
        schema['object_types'][dataset['name']] = {'fields':ndc_fields}
        schema['collections'].append({'name':dataset['name'], 'type':dataset['name'], 'arguments':{},
            'uniqueness_constraints':{'primary':{'unique_columns':['id']}}, 'foreign_keys':{}})
    for relations in RELATIONSHIPS.values():
        for relation in relations.values():
            collection = relation['collection']
            schema['object_types'][collection] = {'fields': {
                field['column']: {'type': {'type': 'nullable', 'underlying_type': {'type': 'named', 'name': ndc_type(field)}}
                                  if field['nullable'] else {'type': 'named', 'name': ndc_type(field)}, 'arguments': {}}
                for field in relation['fields'].values()}}
            schema['collections'].append({'name': collection, 'type': collection, 'arguments': {},
                'uniqueness_constraints': {'primary': {'unique_columns': ['record_id']}}, 'foreign_keys': {}})
    objects = [obj('DataConnectorLink', {'name':'mysql', 'url':{'singleUrl':{'value':connector_url}},
        'headers':{'Authorization':{'value':'Bearer '+connector_key}}, 'schema':{'version':'v0.1','schema':schema,
        'capabilities':{'version':'0.1.6','capabilities':{'query':{'aggregates':{},'variables':{}}, 'mutation':{},
        'relationships':{'relation_comparisons':{},'order_by_aggregate':{}}}}}})]
    for name in ['BigInt','BigDecimal','Json']:
        objects.append(obj('ScalarType', {'name':name,'graphql':{'typeName':name}}))
    for ndc, api in [('STRING','String'),('DATE','String'),('TIMESTAMP','String'),('INT8','Int'),('INT32','Int'),('INT64','BigInt'),('BIGDECIMAL','BigDecimal'),('JSON','Json')]:
        objects.append(obj('DataConnectorScalarRepresentation', {'dataConnectorName':'mysql','dataConnectorScalarType':ndc,'representation':api}))
    for name, scalar, api, ops in [('DateComparison','DATE','String',['_gte','_lt']),
                                  ('TimestampComparison','TIMESTAMP','String',['_gte','_lt']),
                                  ('IdComparison','STRING','String',['_eq']),
                                  ('CategoryComparison','INT64','BigInt',['_eq']),
                                  ('TinyIntComparison','INT8','Int',['_eq','_gte','_lte']),
                                  ('IntComparison','INT32','Int',['_eq','_gte','_lte']),
                                  ('DecimalComparison','BIGDECIMAL','BigDecimal',['_eq','_gte','_lte'])]:
        objects.append(obj('BooleanExpressionType', {'name':name,'operand':{'scalar':{'type':api,
            'comparisonOperators':[{'name':op,'argumentType':api+'!'} for op in ops],
            'dataConnectorOperatorMapping':[{'dataConnectorName':'mysql','dataConnectorScalarType':scalar,'operatorMapping':{op:op for op in ops}}]}},
            'logicalOperators':{'enable':False}, 'isNull':{'enable':False}, 'graphql':{'typeName':name}}))
    for name, api, ndc, result in [('IntSum','Int','INT32','BigInt'), ('DecimalSum','BigDecimal','BIGDECIMAL','BigDecimal')]:
        objects.append(obj('AggregateExpression', {'name':name,'operand':{'scalar':{'aggregatedType':api,
            'aggregationFunctions':[{'name':'sum','returnType':result}],
            'dataConnectorAggregationFunctionMapping':[{'dataConnectorName':'mysql','dataConnectorScalarType':ndc,'functionMapping':{'sum':{'name':'sum'}}}]}},
            'count':{'enable':False},'countDistinct':{'enable':False},'graphql':{'selectTypeName':name+'Stats'}}))
    for dataset in DATASETS:
        name = dataset['typeName']; model = name+'Model'; all_fields = fields(dataset)
        objects.append(obj('ObjectType', {'name':name,'graphql':{'typeName':name},
            'fields':[{'name':key,'type':api_type(field)+('' if field['nullable'] else '!')} for key,field in all_fields.items()],
            'dataConnectorTypeMapping':[{'dataConnectorName':'mysql','dataConnectorObjectType':dataset['name'],
                'fieldMapping':{key:{'column':{'name':field['column']}} for key,field in all_fields.items()}}]}))
        objects.append(obj('BooleanExpressionType', {'name':name+'BoolExp','operand':{'object':{'type':name,
            'comparableFields':[{'fieldName':key,'booleanExpressionType':comparison(field)} for key,field in all_fields.items() if field['kind'] != 'JSON'],
            'comparableRelationships':[{'relationshipName':key, 'booleanExpressionType': relation['typeName']+'BoolExp'}
                for key, relation in RELATIONSHIPS.get(dataset['name'], {}).items()]}}, 'logicalOperators':{'enable':False},'isNull':{'enable':False},'graphql':{'typeName':name+'BoolExp'}}))
        objects.append(obj('AggregateExpression', {'name':name+'Aggregate','operand':{'object':{'aggregatedType':name,
            'aggregatableFields':[{'fieldName':key,'aggregateExpression':'DecimalSum' if dataset['fields'][key]['kind']=='DECIMAL' else 'IntSum'} for key in dataset['aggregateFields']]}},
            'count':{'enable':True},'countDistinct':{'enable':False},'graphql':{'selectTypeName':name+'Stats'}}))
        objects.append(obj('Model', {'name':model,'objectType':name,'source':{'dataConnectorName':'mysql','collection':dataset['name']},
            'filterExpressionType':name+'BoolExp','aggregateExpression':name+'Aggregate',
            'orderableFields':[{'fieldName':key,'orderByDirections':{'enableAll':True}} for key in dataset['orderFields']],
            'graphql':{'selectMany':{'queryRootField':dataset['root']}, 'selectUniques':[], 'orderByExpressionType':name+'OrderBy',
                'aggregate':{'queryRootField':dataset['statsRoot']}, 'filterInputTypeName':name+'FilterInput'}}))
        objects.append(obj('ModelPermissions', {'modelName':model,'permissions':[{'role':dataset['role'],'select':{'filter':{'and':[
            {'fieldComparison':{'field':'userId','operator':'_eq','value':{'sessionVariable':'x-hasura-user-id'}}},
            {'fieldComparison':{'field':'isDeleted','operator':'_eq','value':{'literal':0}}}]}}}]}))
        objects.append(obj('TypePermissions', {'typeName':name,'permissions':[{'role':dataset['role'],'output':{'allowedFields':list(dataset['fields'])}}]}))
        for key, relation in RELATIONSHIPS.get(dataset['name'], {}).items():
            target = relation['typeName']; relation_fields = relation['fields']; model = target+'Model'
            objects.append(obj('ObjectType', {'name': target, 'graphql': {'typeName': target},
                'fields': [{'name': field_name, 'type': api_type(field)+('' if field['nullable'] else '!')}
                           for field_name, field in relation_fields.items()],
                'dataConnectorTypeMapping': [{'dataConnectorName': 'mysql', 'dataConnectorObjectType': relation['collection'],
                    'fieldMapping': {field_name: {'column': {'name': field['column']}} for field_name, field in relation_fields.items()}}]}))
            objects.append(obj('BooleanExpressionType', {'name': target+'BoolExp', 'operand': {'object': {'type': target,
                'comparableFields': [{'fieldName': field_name, 'booleanExpressionType': comparison(relation_fields[field_name])}
                                     for field_name in relation_fields], 'comparableRelationships': []}},
                'logicalOperators': {'enable': False}, 'isNull': {'enable': False}, 'graphql': {'typeName': target+'BoolExp'}}))
            objects.append(obj('Model', {'name': model, 'objectType': target,
                'source': {'dataConnectorName': 'mysql', 'collection': relation['collection']},
                'filterExpressionType': target+'BoolExp', 'orderableFields': []}))
            objects.append(obj('ModelPermissions', {'modelName': model, 'permissions': [{'role': dataset['role'],
                'select': {'filter': {'fieldComparison': {'field': 'userId', 'operator': '_eq',
                    'value': {'sessionVariable': 'x-hasura-user-id'}}}}}]}))
            objects.append(obj('TypePermissions', {'typeName': target, 'permissions': [{'role': dataset['role'],
                # Hasura requires visibility of both mapping fields to expose a relationship.
                # The gateway SDL still exposes only outputFields, keeping recordId internal.
                'output': {'allowedFields': list(dict.fromkeys(relation['outputFields'] + [relation['targetField']]))}}]}))
            objects.append(obj('Relationship', {'name': key, 'sourceType': name,
                'target': {'model': {'name': model, 'subgraph': 'aio', 'relationshipType': 'Object'}},
                'mapping': [{'source': {'fieldPath': [{'fieldName': relation['sourceField']}]},
                             'target': {'modelField': [{'fieldName': relation['targetField']}]}}]}))
    objects.append(obj('GraphqlConfig', {'query':{'rootOperationTypeName':'Query','argumentsInput':{'fieldName':'args'},
        'limitInput':{'fieldName':'limit'},'offsetInput':{'fieldName':'offset'},
        'filterInput':{'fieldName':'where','operatorNames':{'and':'_and','or':'_or','not':'_not','isNull':'_is_null'}},
        'orderByInput':{'fieldName':'orderBy','enumDirectionValues':{'asc':'Asc','desc':'Desc'},'enumTypeNames':[{'directions':['Asc','Desc'],'typeName':'OrderDirection'}]},
        'aggregate':{'filterInputFieldName':'filter','countFieldName':'count','countDistinctFieldName':'countDistinct'}},
        'mutation':{'rootOperationTypeName':'Mutation'}}))
    return {'version':'v3','subgraphs':[{'name':'aio','objects':objects}],'flags':{}}, schema

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'.cache/hasura');args=parser.parse_args()
    key=os.environ.get('AIO_QUERY_WEBHOOK_KEY','')
    if len(key)<32:raise SystemExit('AIO_QUERY_WEBHOOK_KEY must contain at least 32 characters')
    connector_key=os.environ.get('AIO_QUERY_CONNECTOR_KEY','')
    if len(connector_key)<32:raise SystemExit('AIO_QUERY_CONNECTOR_KEY must contain at least 32 characters')
    metadata,schema=build(os.environ.get('AIO_QUERY_CONNECTOR_URL','http://mysql-connector:8080'),connector_key)
    auth={'version':'v4','definition':{'mode':{'webhook':{'url':{'value':os.environ.get('AIO_QUERY_AUTH_WEBHOOK_URL','http://gateway:45680/internal/hasura/auth')},
        'method':'POST','customHeadersConfig':{'body':{'headers':{'forward':['authorization']}},'headers':{'additional':{'X-AIO-Webhook-Key':key}}}}}}}
    write_bundle(args.output, [('open_dd.json',metadata),('auth_config.json',auth),('ndc-schema.json',schema),('configuration.json',connector_config())])
    print('Built '+str(len(DATASETS))+' dataset OpenDD bundle at '+str(args.output))
