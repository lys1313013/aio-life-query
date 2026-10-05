#!/usr/bin/env python3
"""Build the audited single-table OpenDD bundle locally; never introspect/import every table."""
import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELDS = {'id': ('STRING', 'String!', 'id'), 'date': ('DATE', 'String', 'date'),
          'categoryId': ('INT64', 'BigInt', 'category_id'), 'durationMinutes': ('INT32', 'Int', 'duration'),
          'userId': ('INT64', 'BigInt', 'user_id'), 'isDeleted': ('INT8', 'Int', 'is_deleted')}

def obj(kind, definition):
    return {'kind': kind, 'version': 'v1', 'definition': definition}

def connector_config():
    columns = []
    for name, kind, nullable in [('id','VARCHAR',False), ('date','DATE',False), ('category_id','BIGINT',True),
                                 ('duration','SMALLINT UNSIGNED',False), ('user_id','BIGINT',False), ('is_deleted','TINYINT',False)]:
        columns.append({'name':name, 'description':None, 'type':kind, 'numeric_precision':None, 'numeric_scale':None,
                        'nullable':nullable, 'auto_increment':False, 'is_primarykey':name=='id'})
    return {'jdbcUrl':{'variable':'JDBC_URL'}, 'jdbcProperties':{'connectTimeout':'2000','socketTimeout':'6000',
            'sessionVariables':'max_execution_time=5000,group_concat_max_len=1000000', 'allowMultiQueries':'false', 'allowLoadLocalInfile':'false'},
            'tables':[{'tableName':'time_record','tableType':'TABLE','description':None,'columns':columns,'pks':['id'],'fks':{}}],
            'functions':[], 'nativeQueries':{}}

def build(connector_url, connector_key):
    scalar_types = {}
    for name, representation in [('STRING', 'string'), ('DATE', 'date'), ('INT8', 'int8'), ('INT32', 'int32'), ('INT64', 'int64')]:
        ops = {'_eq': {'type': 'equal'}}
        if name != 'STRING':
            ops.update({op: {'type': 'custom', 'argument_type': {'type': 'named', 'name': name}} for op in ['_gte', '_lt', '_lte']})
        scalar_types[name] = {'representation': {'type': representation}, 'comparison_operators': ops, 'aggregate_functions': {}}
    scalar_types['INT32']['aggregate_functions']['sum'] = {'result_type': {'type': 'named', 'name': 'INT64'}}
    ndc_fields = {}
    for name, (ndc_type, api_type, column) in FIELDS.items():
        t = {'type': 'named', 'name': ndc_type}
        if not api_type.endswith('!'): t = {'type': 'nullable', 'underlying_type': t}
        ndc_fields[column] = {'type': t, 'arguments': {}}
    schema = {'scalar_types': scalar_types, 'object_types': {'time_record': {'fields': ndc_fields}},
              'collections': [{'name': 'time_record', 'type': 'time_record', 'arguments': {},
                               'uniqueness_constraints': {'primary': {'unique_columns': ['id']}}, 'foreign_keys': {}}],
              'functions': [], 'procedures': []}
    objects = [obj('DataConnectorLink', {'name': 'mysql', 'url': {'singleUrl': {'value': connector_url}}, 'headers': {'Authorization':{'value':'Bearer '+connector_key}},
               'schema': {'version': 'v0.1', 'schema': schema, 'capabilities': {'version': '0.1.6', 'capabilities': {
                   'query': {'aggregates': {}, 'variables': {}}, 'mutation': {}, 'relationships': {'relation_comparisons': {}, 'order_by_aggregate': {}}}}}})]
    objects += [obj('ScalarType', {'name': 'BigInt', 'graphql': {'typeName': 'BigInt'}})]
    for ndc, api in [('STRING','String'),('DATE','String'),('INT8','Int'),('INT32','Int'),('INT64','BigInt')]:
        objects.append(obj('DataConnectorScalarRepresentation', {'dataConnectorName': 'mysql', 'dataConnectorScalarType': ndc, 'representation': api}))
    objects.append(obj('ObjectType', {'name': 'TimeRecord', 'graphql': {'typeName': 'TimeRecord'},
        'fields': [{'name': name, 'type': api} for name, (_, api, _) in FIELDS.items()],
        'dataConnectorTypeMapping': [{'dataConnectorName': 'mysql', 'dataConnectorObjectType': 'time_record',
            'fieldMapping': {name: {'column': {'name': column}} for name, (_,_,column) in FIELDS.items()}}]}))
    for name, scalar, api, ops in [('DateComparison','DATE','String',['_gte','_lt']), ('IdComparison','STRING','String',['_eq']),
                                  ('CategoryComparison','INT64','BigInt',['_eq']), ('DeletedComparison','INT8','Int',['_eq']), ('IntComparison','INT32','Int',['_eq','_gte','_lte'])]:
        objects.append(obj('BooleanExpressionType', {'name': name, 'operand': {'scalar': {'type': api,
            'comparisonOperators': [{'name': op, 'argumentType': api+'!'} for op in ops],
            'dataConnectorOperatorMapping': [{'dataConnectorName':'mysql','dataConnectorScalarType':scalar,'operatorMapping':{op:op for op in ops}}]}},
            'logicalOperators': {'enable':False}, 'isNull':{'enable':False}, 'graphql':{'typeName':name}}))
    objects.append(obj('BooleanExpressionType', {'name':'TimeRecordBoolExp','operand':{'object':{'type':'TimeRecord',
        'comparableFields':[{'fieldName':name,'booleanExpressionType':kind} for name,kind in [('date','DateComparison'),('id','IdComparison'),('categoryId','CategoryComparison'),('durationMinutes','IntComparison'),('userId','CategoryComparison'),('isDeleted','DeletedComparison')]],
        'comparableRelationships':[]}},'logicalOperators':{'enable':False},'isNull':{'enable':False},'graphql':{'typeName':'TimeRecordBoolExp'}}))
    objects.append(obj('AggregateExpression', {'name':'DurationAggregate','operand':{'scalar':{'aggregatedType':'Int',
        'aggregationFunctions':[{'name':'sum','returnType':'BigInt'}], 'dataConnectorAggregationFunctionMapping':[{'dataConnectorName':'mysql','dataConnectorScalarType':'INT32','functionMapping':{'sum':{'name':'sum'}}}]}},
        'count':{'enable':False},'countDistinct':{'enable':False},'graphql':{'selectTypeName':'DurationStats'}}))
    objects.append(obj('AggregateExpression', {'name':'TimeAggregate','operand':{'object':{'aggregatedType':'TimeRecord',
        'aggregatableFields':[{'fieldName':'durationMinutes','aggregateExpression':'DurationAggregate'}]}},
        'count':{'enable':True},'countDistinct':{'enable':False},'graphql':{'selectTypeName':'TimeRecordStats'}}))
    objects.append(obj('Model', {'name':'TimeRecords','objectType':'TimeRecord','source':{'dataConnectorName':'mysql','collection':'time_record'},
        'filterExpressionType':'TimeRecordBoolExp','aggregateExpression':'TimeAggregate',
        'orderableFields':[{'fieldName':name,'orderByDirections':{'enableAll':True}} for name in ['id','date','durationMinutes']],
        'graphql':{'selectMany':{'queryRootField':'timeRecords'},'selectUniques':[],'orderByExpressionType':'TimeRecordOrderBy',
                   'aggregate':{'queryRootField':'timeRecordStats'},'filterInputTypeName':'TimeRecordFilterInput'}}))
    objects.append(obj('ModelPermissions', {'modelName':'TimeRecords','permissions':[{'role':'ai_time_reader','select':{'filter':{'and':[
        {'fieldComparison':{'field':'userId','operator':'_eq','value':{'sessionVariable':'x-hasura-user-id'}}},
        {'fieldComparison':{'field':'isDeleted','operator':'_eq','value':{'literal':0}}}]}}}]}))
    objects.append(obj('TypePermissions', {'typeName':'TimeRecord','permissions':[{'role':'ai_time_reader','output':{'allowedFields':['id','date','categoryId','durationMinutes']}}]}))
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
    args.output.mkdir(parents=True,exist_ok=True)
    for name,value in [('open_dd.json',metadata),('auth_config.json',auth),('ndc-schema.json',schema),('configuration.json',connector_config())]:
        path=args.output/name;path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');path.chmod(0o640)
    print('Built time_record OpenDD bundle at '+str(args.output))
