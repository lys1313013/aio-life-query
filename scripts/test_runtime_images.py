#!/usr/bin/env python3
"""Release gate: real runtime images, fictitious users, isolated PostgreSQL/Redis."""
from decimal import Decimal
import json
import os
import re
from pathlib import Path
import subprocess

from runtime_test_stack import ROOT, RuntimeStack, docker, metadata, request

USER_A = '8000000000000000001'
USER_B = '8000000000000000002'


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def fixture():
    lines = []
    for dataset in metadata.DATASETS:
        if dataset.get('nativeSql'):
            continue
        columns = [field['column'] + ' ' + field['sqlType'] + (' NOT NULL' if not field['nullable'] else '')
                   for field in dataset['fields'].values()]
        lines.append('CREATE TABLE ' + dataset['name'] + '(' + ','.join(columns) + ', PRIMARY KEY(id));')
        for index, (owner, deleted) in enumerate([(101, 0), (101, 0), (102, 0), (101, 1)]):
            values = []
            for name, field in dataset['fields'].items():
                kind = field['kind']
                value = 'fixture'
                if kind == 'ID': value = 21
                elif kind == 'INT': value = 26 if index == 0 else 14 if owner == 101 else 1000
                elif kind == 'DECIMAL': value = '19.99' if index == 0 else '20.02' if owner == 101 else '999999.00'
                elif kind == 'DATE': value = '2026-10-01'
                elif kind == 'DATETIME': value = '2026-10-01 10:00:00'
                elif kind == 'STATUS': value = 'completed'
                elif kind == 'JSON': value = '{"fixture":true,"pages":[1,2]}'
                if name == 'id': value = 9007199254740993 + index
                elif name == 'userId': value = owner
                elif name in ['createUser', 'updateUser']: value = 999
                elif name == 'isDeleted': value = deleted
                elif name == 'categoryId': value = 1
                elif name == 'exerciseTypeId': value = 21
                elif name == 'startMinute': value = 0
                elif name == 'endMinute': value = 59
                elif name == 'durationMinutes': value = 60
                values.append(literal(value))
            lines.append('INSERT INTO ' + dataset['name'] + '(' + ','.join(f['column'] for f in dataset['fields'].values())
                         + ') VALUES (' + ','.join(values) + ');')
    lines += [
        'CREATE TABLE time_tracker_category(id BIGINT PRIMARY KEY,user_id BIGINT,template_id BIGINT,name VARCHAR(100),is_deleted SMALLINT);',
        "INSERT INTO time_tracker_category VALUES(1,0,NULL,'公共分类',0),(2,101,1,'休息',0),(3,102,1,'学习',0);",
        'CREATE TABLE user_dict_data(id BIGINT PRIMARY KEY,user_id BIGINT,template_id BIGINT,dict_type VARCHAR(80),dict_label VARCHAR(100),is_readonly VARCHAR(1),is_deleted SMALLINT);',
        "INSERT INTO user_dict_data VALUES(21,0,NULL,'exercise_type','公共运动','N',0),(22,0,NULL,'income_type','公共收入','N',0),(31,101,21,'exercise_type','俯卧撑','N',0),(32,102,21,'exercise_type','慢跑','N',0),(33,101,22,'income_type','工资','N',0),(34,102,22,'income_type','稿费','N',0);",
        'CREATE TABLE income(user_id BIGINT,inc_type_id BIGINT,inc_date DATE,amt NUMERIC(18,2),is_deleted SMALLINT);',
        "INSERT INTO income VALUES(101,22,'2026-10-01',19.99,0),(101,22,'2026-10-02',20.02,0),(102,22,'2026-10-01',999999.00,0),(101,22,'2026-10-03',888888.00,1);",
        'CREATE TABLE sys_user(id BIGINT PRIMARY KEY,password TEXT);',
        "INSERT INTO sys_user VALUES(101,'must-not-be-readable');",
        (ROOT / 'deploy/readonly-account.sql.example').read_text().replace('aio_query_read', 'fixture_read').replace('REPLACE_WITH_RANDOM_PASSWORD', 'fixture-read'),
    ]
    return re.sub(r'\b(101|102)\b', lambda match: USER_A if match[0] == '101' else USER_B, '\n'.join(lines))


def where(dataset):
    return dataset['rangeFields'][0] + ':{_gte:"2026-10-01",_lt:"2026-11-01"}'


def check_schema(stack):
    docker('exec', stack.connector, '/bin/ndc-postgres', 'check-health')
    response = docker('run', '--rm', '-e', 'CONNECTION_URI=postgresql://unused:unused@127.0.0.1/unused',
        '-v', str(stack.config) + ':/etc/connector:ro', '--group-add', str(os.getgid()),
        os.environ.get('AIO_QUERY_CONNECTOR_IMAGE', 'aio-life-query-postgresql-connector:local'),
        'print-schema-and-capabilities', '--configuration', '/etc/connector')
    actual = json.loads(response)['schema']
    expected = json.loads((stack.config / 'ndc-schema.json').read_text())
    for schema in [actual, expected]: schema['collections'].sort(key=lambda item: item['name'])
    assert actual == expected, 'Generated schema differs from the official connector'
    assert actual['procedures'] == [] and actual['request_arguments'] is None
    print('PASS official schema, disabled mutations and request connection overrides', flush=True)


def check_rows(stack):
    for dataset in metadata.DATASETS:
        stack.reset_rates()
        selections = ' '.join(dataset['fields'])
        graphql = '{' + dataset['root'] + '(where:{' + where(dataset) + '}){' + selections + '}}'
        rows = stack.query({'query': graphql})[dataset['root']]
        other = stack.query({'query': graphql}, 'user-b')[dataset['root']]
        expected = 1 if dataset.get('nativeSql') else 2
        assert len(rows) == expected and len(other) == 1, (dataset['name'], rows, other)
        assert all(row['userId'] == USER_A and row['isDeleted'] == 0 for row in rows)
        assert all(row['userId'] == USER_B for row in other)
        if not dataset.get('nativeSql'):
            assert rows[0]['id'] in ['9007199254740993', '9007199254740994']
        stats = '{' + dataset['statsRoot'] + '(where:{' + where(dataset) + '}){count}}'
        assert stack.query({'query': stats})[dataset['statsRoot']]['count'] == str(expected)
        empty = stats.replace('2026-10-01', '2099-10-01').replace('2026-11-01', '2099-11-01')
        assert stack.query({'query': empty})[dataset['statsRoot']]['count'] == '0'
        for field in dataset['aggregateFields']:
            empty_sum = empty.replace('{count}', '{value:' + field + '{total:sum max min}}')
            assert stack.query({'query': empty_sum})[dataset['statsRoot']]['value'] == {'total':None,'max':None,'min':None}
        print('PASS dataset', dataset['name'], 'all fields, large IDs, ownership, soft deletion and aggregate count', flush=True)


def check_relations_and_money(stack):
    stack.reset_rates()
    for root, date, relation, expected in [('timeRecords','date','category','休息'),
                                           ('exerciseRecords','exerciseDate','exerciseType','俯卧撑'),
                                           ('incomeMonths','month','incomeType','工资')]:
        query = '{' + root + '(where:{' + date + ':{_gte:"2026-10-01",_lt:"2026-11-01"},' + relation + ':{name:{_eq:"' + expected + '"}}}){id ' + relation + '{id name}}}'
        rows = stack.query({'query': query})[root]
        assert rows and all(row[relation]['name'] == expected for row in rows)
        assert stack.query({'query': query}, 'user-b')[root] == []
    stats = stack.query({'query':'{eventRecordStats(where:{eventDate:{_gte:"2026-10-01",_lt:"2026-11-01"}}){ticketPrice{sum max min}}}'})
    amount = stats['eventRecordStats']['ticketPrice']
    assert Decimal(amount['sum']) == Decimal('40.01')
    assert Decimal(amount['max']) == Decimal('20.02') and Decimal(amount['min']) == Decimal('19.99')
    salary = stack.query({'query':'{incomeMonthStats(where:{month:{_gte:"2026-10-01",_lt:"2026-11-01"},incomeType:{name:{_eq:"工资"}}}){monthlyAmount{max}}}'})
    assert Decimal(salary['incomeMonthStats']['monthlyAmount']['max']) == Decimal('40.01')
    progress = stack.query({'query':'{exerciseRecordStats(where:{exerciseDate:{_gte:"2026-10-01",_lt:"2026-11-01"}}){exerciseCount{sum max min}}}'})
    assert progress['exerciseRecordStats']['exerciseCount'] == {'sum':'40','max':'26','min':'14'}
    days = stack.query({'query':'{timeRecordStats(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"},category:{name:{_eq:"休息"}}}){date{countDistinct}}}'})
    assert days['timeRecordStats']['date']['countDistinct'] == '1'
    print('PASS category/type overrides, relationships, exact money, monthly income and target metrics', flush=True)


def check_boundaries(stack):
    stack.reset_rates()
    assert request(stack.connector_url + '/schema')[0] == 401
    assert request(stack.engine_url + '/graphql', {'query':'{__typename}'})[0] in [401, 403]
    assert request(stack.url + '/api/v1/datasets')[0] == 401
    body = {'query':'{timeRecordStats(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"}}){count}}'}
    stack.locked.add(('user-a','time_record'))
    status, data = request(stack.url + '/api/v1/queries/execute', body, 'user-a')
    assert status == 403 and data['rscode'] == 'SECONDARY_LOCK_REQUIRED'
    stack.locked.clear()
    del stack.active_tokens['user-a']
    assert request(stack.url + '/api/v1/queries/execute', body, 'user-a')[0] == 401
    stack.active_tokens['user-a'] = USER_A
    for sql in ['UPDATE time_record SET duration_minutes=duration_minutes WHERE false', 'SELECT password FROM sys_user']:
        result = subprocess.run(['docker','exec','-e','PGPASSWORD=fixture-read',stack.postgres,'psql','-h','127.0.0.1','-U','fixture_read','-d','aio_life','-v','ON_ERROR_STOP=1','-c',sql], capture_output=True,text=True)
        assert result.returncode != 0 and ('permission denied' in result.stderr or 'read-only' in result.stderr), result.stderr
    deadline = subprocess.run(['docker','exec','-e','PGPASSWORD=fixture-read',stack.postgres,'psql','-h','127.0.0.1','-U','fixture_read','-d','aio_life','-c','SELECT pg_sleep(6)'],capture_output=True,text=True,timeout=9)
    assert deadline.returncode != 0 and 'statement timeout' in deadline.stderr
    image = os.environ.get('AIO_QUERY_CONNECTOR_IMAGE', 'aio-life-query-postgresql-connector:local')
    missing = subprocess.run(['docker','run','--rm',image],capture_output=True,text=True)
    assert missing.returncode == 64 and 'HASURA_SERVICE_TOKEN_SECRET' in missing.stderr
    print('PASS authentication, revoked credential, secondary lock, readonly columns, timeout and fail-closed startup', flush=True)


def main():
    with RuntimeStack(fixture(), users=(USER_A, USER_B)) as stack:
        check_schema(stack)
        check_rows(stack)
        check_relations_and_money(stack)
        check_boundaries(stack)
        (stack.base / 'result.json').write_text(json.dumps({'result':'passed','datasets':7,'identities':[USER_A,USER_B],'productionDataUsed':False}) + '\n')
        print('PASS formal runtime release acceptance; evidence: ' + str(stack.base), flush=True)


if __name__ == '__main__':
    main()
