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

NATIVE_TYPES = {
    'BIGINT': ('int8', 'int64', 'int64AsString', 'BigInt'),
    'VARCHAR': ('varchar', 'string', 'string', 'String'),
    'TEXT': ('text', 'string', 'string', 'String'),
    'JSONB': ('jsonb', 'json', 'json', 'Json'),
    'DATE': ('date', 'date', 'date', 'String'),
    'TIMESTAMP': ('timestamp', 'timestamp', 'timestamp', 'String'),
    'INTEGER': ('int4', 'int32', 'int32', 'Int'),
    'SMALLINT': ('int2', 'int16', 'int16', 'Int'),
    'NUMERIC': ('numeric', 'bigdecimal', 'bigDecimalAsString', 'BigDecimal'),
}

def ndc_type(field):
    return NATIVE_TYPES[field['sqlType']][0]

def api_type(field):
    return NATIVE_TYPES[field['sqlType']][3]

def comparison(field):
    return {'int8':'CategoryComparison', 'varchar':'IdComparison', 'text':'IdComparison', 'date':'DateComparison',
            'timestamp':'TimestampComparison', 'int4':'IntComparison', 'int2':'TinyIntComparison',
            'numeric':'DecimalComparison'}[ndc_type(field)]

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
    """Official ndc-postgres v3 configuration; no live database introspection."""
    scalar = {}
    for native, _, representation, _ in NATIVE_TYPES.values():
        operators = {} if native == 'jsonb' else {
            '_eq': {'operatorName':'=', 'operatorKind':'equal', 'argumentType':native, 'isInfix':True}}
        if native not in ['varchar', 'text', 'jsonb']:
            operators.update({name: {'operatorName':sql, 'operatorKind':'custom', 'argumentType':native, 'isInfix':True}
                              for name, sql in [('_gte','>='), ('_lt','<'), ('_lte','<=')]})
        aggregates = {}
        if native in ['int4', 'numeric']:
            # Use the qualified PostgreSQL function to retain SQL's NULL on empty
            # input; the NDC built-in `sum` deliberately coalesces that to zero.
            aggregates = {'pg_catalog.sum' if name == 'sum' else name:
                          {'returnType': 'int8' if native == 'int4' and name == 'sum' else native}
                          for name in ['sum', 'max', 'min']}
        scalar[native] = {'typeName':native, 'schemaName':'pg_catalog', 'aggregateFunctions':aggregates,
                          'comparisonOperators':operators, 'typeRepresentation':representation}

    def columns(values):
        return {field['column']: {'name':field['column'], 'type':{'scalarType':ndc_type(field)},
                                 'nullable':'nullable' if field['nullable'] else 'nonNullable'}
                for field in values.values()}

    tables = {dataset['name']: {'schemaName':'public', 'tableName':dataset['name'],
                               'columns':columns(dataset['fields']), 'uniquenessConstraints':{'primary':['id']},
                               'foreignRelations':{}}
              for dataset in DATASETS if not dataset.get('nativeSql')}
    natives = {dataset['name']: dataset for dataset in DATASETS if dataset.get('nativeSql')}
    natives.update({relation['collection']: relation for relations in RELATIONSHIPS.values() for relation in relations.values()})
    queries = {name: {'sql':{'inline':(ROOT / definition['nativeSql']).read_text().strip().rstrip(';')},
                      'columns':columns(definition['fields']), 'arguments':{}}
               for name, definition in natives.items()}
    return {'version':'5',
            'connectionSettings':{'connectionUri':{'variable':'CONNECTION_URI'},
                                  'poolSettings':{'maxConnections':10, 'poolTimeout':3, 'idleTimeout':60,
                                                  'checkConnectionAfterIdle':30, 'connectionLifetime':300},
                                  'isolationLevel':'ReadCommitted'},
            'metadata':{'tables':tables, 'types':{'scalar':scalar, 'composite':{}},
                        'nativeOperations':{'queries':queries, 'mutations':{}}},
            'mutationsVersion':None}


def connector_schema(config):
    """NDC 0.2 schema, checked against official print-schema-and-capabilities in CI."""
    scalar_types = {}
    for native, representation, _, _ in NATIVE_TYPES.values():
        definition = config['metadata']['types']['scalar'][native]
        ops = {name: {'type':'equal'} if operator['operatorKind'] == 'equal' else
                      {'type':'custom','argument_type':{'type':'named','name':native}}
               for name, operator in definition['comparisonOperators'].items()}
        aggregates = {}
        for function, output in definition['aggregateFunctions'].items():
            if function in ['max', 'min']:
                aggregates[function] = {'type':function}
            else:
                aggregates[function] = {'type':'custom','result_type':{'type':'nullable','underlying_type':{'type':'named','name':output['returnType']}}}
        scalar_types[native] = {'representation':{'type':representation}, 'comparison_operators':ops,
                                'aggregate_functions':aggregates, 'extraction_functions':{}}
    schema = {'scalar_types':scalar_types, 'object_types':{}, 'collections':[], 'functions':[], 'procedures':[],
              'capabilities':{'query':{'aggregates':{'count_scalar_type':'int8'}}}, 'request_arguments':None}
    for native in [False, True]:
        definitions = config['metadata']['nativeOperations']['queries'] if native else config['metadata']['tables']
        for name, definition in sorted(definitions.items()):
            output = {}
            for column, field in definition['columns'].items():
                kind = {'type':'named','name':field['type']['scalarType']}
                if field['nullable'] == 'nullable': kind = {'type':'nullable','underlying_type':kind}
                output[column] = {'type':kind}
            schema['object_types'][name] = {'fields':output, 'foreign_keys':{}}
            schema['collections'].append({'name':name,'type':name,'arguments':{},
                                           'uniqueness_constraints':{} if native else
                                           {'primary':{'unique_columns':['id']}}})
    return schema


def build(connector_url, connector_key):
    schema = connector_schema(connector_config())
    capabilities = {'version':'0.2.4','capabilities':{
        'query':{'aggregates':{},'variables':{},'explain':{},
                 'nested_fields':{'filter_by':{},'order_by':{},'aggregates':{}},'exists':{'nested_collections':{}}},
        'mutation':{'transactional':{},'explain':{}},
        'relationships':{'relation_comparisons':{},'order_by_aggregate':{},'nested':{'array':{}}}}}
    objects = [obj('DataConnectorLink', {'name':'postgresql', 'url':{'singleUrl':{'value':connector_url}},
        'headers':{'Authorization':{'value':'Bearer '+connector_key}},
        'schema':{'version':'v0.2','schema':schema,'capabilities':capabilities}})]
    for name in ['BigInt','BigDecimal','Json']:
        objects.append(obj('ScalarType', {'name':name,'graphql':{'typeName':name}}))
    for ndc, _, _, api in NATIVE_TYPES.values():
        objects.append(obj('DataConnectorScalarRepresentation', {'dataConnectorName':'postgresql','dataConnectorScalarType':ndc,'representation':api}))
    for name, scalars, api, ops in [('DateComparison',['date'],'String',['_gte','_lt']),
                                   ('TimestampComparison',['timestamp'],'String',['_gte','_lt']),
                                   ('IdComparison',['varchar','text'],'String',['_eq']),
                                   ('CategoryComparison',['int8'],'BigInt',['_eq']),
                                   ('TinyIntComparison',['int2'],'Int',['_eq','_gte','_lte']),
                                   ('IntComparison',['int4'],'Int',['_eq','_gte','_lte']),
                                   ('DecimalComparison',['numeric'],'BigDecimal',['_eq','_gte','_lte'])]:
        objects.append(obj('BooleanExpressionType', {'name':name,'operand':{'scalar':{'type':api,
            'comparisonOperators':[{'name':op,'argumentType':api+'!'} for op in ops],
            'dataConnectorOperatorMapping':[{'dataConnectorName':'postgresql','dataConnectorScalarType':scalar,'operatorMapping':{op:op for op in ops}} for scalar in scalars]}},
            'logicalOperators':{'enable':False}, 'isNull':{'enable':False}, 'graphql':{'typeName':name}}))
    for name, api, ndc in [('IntSum','Int','int4'), ('DecimalSum','BigDecimal','numeric')]:
        objects.append(obj('AggregateExpression', {'name':name,'operand':{'scalar':{'aggregatedType':api,
            'aggregationFunctions':[{'name':function,'returnType':'BigInt' if ndc == 'int4' and function == 'sum' else api} for function in ['sum','max','min']],
            'dataConnectorAggregationFunctionMapping':[{'dataConnectorName':'postgresql','dataConnectorScalarType':ndc,'functionMapping':{function:{'name':'pg_catalog.sum' if function == 'sum' else function} for function in ['sum','max','min']}}]}},
            'count':{'enable':False},'countDistinct':{'enable':False},'graphql':{'selectTypeName':name+'Stats'}}))
    objects.append(obj('AggregateExpression', {'name':'DateDistinct','operand':{'scalar':{'aggregatedType':'String','aggregationFunctions':[],'dataConnectorAggregationFunctionMapping':[]}}, 'count':{'enable':False},'countDistinct':{'enable':True,'returnType':'BigInt'},'graphql':{'selectTypeName':'DateDistinctStats'}}))
    for dataset in DATASETS:
        name = dataset['typeName']; model = name+'Model'; all_fields = fields(dataset)
        objects.append(obj('ObjectType', {'name':name,'graphql':{'typeName':name},
            'fields':[{'name':key,'type':api_type(field)+('' if field['nullable'] else '!')} for key,field in all_fields.items()],
            'dataConnectorTypeMapping':[{'dataConnectorName':'postgresql','dataConnectorObjectType':dataset['name'],
                'fieldMapping':{key:{'column':{'name':field['column']}} for key,field in all_fields.items()}}]}))
        objects.append(obj('BooleanExpressionType', {'name':name+'BoolExp','operand':{'object':{'type':name,
            'comparableFields':[{'fieldName':key,'booleanExpressionType':comparison(field)} for key,field in all_fields.items() if field['kind'] != 'JSON'],
            'comparableRelationships':[{'relationshipName':key, 'booleanExpressionType': relation['typeName']+'BoolExp'}
                for key, relation in RELATIONSHIPS.get(dataset['name'], {}).items()]}}, 'logicalOperators':{'enable':False},'isNull':{'enable':False},'graphql':{'typeName':name+'BoolExp'}}))
        objects.append(obj('AggregateExpression', {'name':name+'Aggregate','operand':{'object':{'aggregatedType':name,
            'aggregatableFields':[{'fieldName':key,'aggregateExpression':'DecimalSum' if dataset['fields'][key]['kind']=='DECIMAL' else 'IntSum'} for key in dataset['aggregateFields']] + [{'fieldName':key,'aggregateExpression':'DateDistinct'} for key in dataset.get('distinctFields',[])]}},
            'count':{'enable':True,'returnType':'BigInt'},'countDistinct':{'enable':False},'graphql':{'selectTypeName':name+'Stats'}}))
        objects.append(obj('Model', {'name':model,'objectType':name,'source':{'dataConnectorName':'postgresql','collection':dataset['name']},
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
                'dataConnectorTypeMapping': [{'dataConnectorName': 'postgresql', 'dataConnectorObjectType': relation['collection'],
                    'fieldMapping': {field_name: {'column': {'name': field['column']}} for field_name, field in relation_fields.items()}}]}))
            objects.append(obj('BooleanExpressionType', {'name': target+'BoolExp', 'operand': {'object': {'type': target,
                'comparableFields': [{'fieldName': field_name, 'booleanExpressionType': comparison(relation_fields[field_name])}
                                     for field_name in relation_fields], 'comparableRelationships': []}},
                'logicalOperators': {'enable': False}, 'isNull': {'enable': False}, 'graphql': {'typeName': target+'BoolExp'}}))
            objects.append(obj('Model', {'name': model, 'objectType': target,
                'source': {'dataConnectorName': 'postgresql', 'collection': relation['collection']},
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
    # Preserve the webhook's JSON string for BIGINT identities. Legacy session
    # casting parses custom scalars as numbers, which loses precision downstream.
    return {'version':'v3','subgraphs':[{'name':'aio','objects':objects}],'flags':{'json_session_variables':True}}, schema

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'.cache/hasura');args=parser.parse_args()
    key=os.environ.get('AIO_QUERY_WEBHOOK_KEY','')
    if len(key)<32:raise SystemExit('AIO_QUERY_WEBHOOK_KEY must contain at least 32 characters')
    connector_key=os.environ.get('AIO_QUERY_CONNECTOR_KEY','')
    if len(connector_key)<32:raise SystemExit('AIO_QUERY_CONNECTOR_KEY must contain at least 32 characters')
    metadata,schema=build(os.environ.get('AIO_QUERY_CONNECTOR_URL','http://postgresql-connector:8080'),connector_key)
    auth={'version':'v4','definition':{'mode':{'webhook':{'url':{'value':os.environ.get('AIO_QUERY_AUTH_WEBHOOK_URL','http://aio-life-query:45680/internal/hasura/auth')},
        'method':'POST','customHeadersConfig':{'body':{'headers':{'forward':['authorization']}},'headers':{'additional':{'X-AIO-Webhook-Key':key}}}}}}}
    write_bundle(args.output, [('open_dd.json',metadata),('auth_config.json',auth),('ndc-schema.json',schema),('configuration.json',connector_config())])
    print('Built '+str(len(DATASETS))+' dataset OpenDD bundle at '+str(args.output))
