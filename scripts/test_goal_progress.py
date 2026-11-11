#!/usr/bin/env python3
"""Goal scenarios through real gateway / Hasura / Connector / isolated PostgreSQL.

Uses fictitious users and a local authorization fixture. Optional Java acceptance
also exercises the business goal controller, actual migration and snapshot writes.
"""
import json
from decimal import Decimal
import os
from pathlib import Path
import re
import subprocess
import test_category_relationship as runner

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT.parent / 'aio-life-server'


def acceptance(query, postgres, base, url):
    def execute(name, user='user-a'):
        config = json.loads((ROOT / ('examples/goal-' + name + '.json')).read_text())
        variables = json.loads(config['progressVariables']) | {'start': '2026-01-01', 'end': '2027-01-01'}
        result = query({'query': config['progressQuery'], 'variables': variables}, user)
        (base / ('goal-' + name + '-' + user + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2))
        return result

    assert execute('pushups')['progress']['exerciseCount']['max'] == '26'
    assert Decimal(execute('salary')['progress']['monthlyAmount']['max']) == Decimal('15000.00')
    assert execute('pushups', 'user-b')['progress']['exerciseCount']['max'] == '1000'
    assert Decimal(execute('salary', 'user-b')['progress']['monthlyAmount']['max']) == Decimal('999999.00')
    # 通过真实派生关联验证个人覆盖、公共只读、停用历史、错误域及其他用户隔离。
    exercise_rows = query({'query': '{exerciseRecords(where:{exerciseDate:{_gte:"2026-01-01",_lt:"2027-01-01"}}){id kind:exerciseType{key:id label:name}}}'})['exerciseRecords']
    exercise_types = {row['id']: row['kind'] for row in exercise_rows}
    assert exercise_types['11']['label'] == '拉伸', exercise_types
    assert exercise_types['13']['label'] == '只读运动', exercise_types
    assert exercise_types['14']['label'] == '私人运动', exercise_types
    assert exercise_types['18']['label'] == '停用运动', exercise_types
    assert all(exercise_types[key] is None for key in ['15', '16', '17']), exercise_types
    assert '3' not in exercise_types and '4' not in exercise_types, exercise_types
    other_types = query({'query': '{exerciseRecords(where:{exerciseDate:{_gte:"2026-01-01",_lt:"2027-01-01"}}){id exerciseType{name}}}'}, 'user-b')['exerciseRecords']
    assert next(row for row in other_types if row['id'] == '12')['exerciseType']['name'] == '拉伸原名', other_types
    income_rows = query({'query': '{incomeMonths(where:{month:{_gte:"2026-01-01",_lt:"2027-01-01"}}){incomeTypeId kind:incomeType{id name}}}'})['incomeMonths']
    income_types = {row['incomeTypeId']: row['kind'] for row in income_rows}
    assert income_types['99013']['name'] == '固定收入', income_types
    assert income_types['99015']['name'] == '只读收入', income_types
    assert income_types['99019']['name'] == '工资', income_types
    assert all(income_types[key] is None for key in ['99017', '99018', '9007199254740993']), income_types
    salary_sum = query({'query': '{incomeMonthStats(where:{month:{_gte:"2026-01-01",_lt:"2027-01-01"},incomeType:{name:{_eq:"工资"}}}){monthlyAmount{sum}}}'})
    assert Decimal(salary_sum['incomeMonthStats']['monthlyAmount']['sum']) == Decimal('30004.99'), salary_sum
    print('PASS: exercise/income type names, overrides, readonly labels, disabled history, same-name matches, wrong-domain and cross-user isolation', flush=True)
    # Isolate the exact sleep category, retain previous category regression fixtures.
    sleep = query({'query': 'query{timeRecordStats(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"},startTime:{_eq:0},category:{id:{_eq:"1"}}}){date{countDistinct}}}'})
    assert sleep['timeRecordStats']['date']['countDistinct'] == '1', sleep
    # Duplicate zero-minute records cannot inflate date counts; daytime sleep excluded.
    runner.docker('exec', '-i', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'aio_life', sql="""
INSERT INTO time_record(id,date,category_id,user_id,start_time,end_time) VALUES
 ('duplicate-midnight','2026-10-01',1,101,0,10),
 ('afternoon-nap','2026-10-10',1,101,780,840),
 ('sleeping-at-0001','2026-10-11',1,101,1,420),
 ('exact-zero','2026-10-12',1,101,0,0),
 ('another-midnight','2026-10-13',1,101,0,420);
""")
    # 名称关联还包含有效名称为休息的公共覆盖和私有分类（10-02、10-04）。
    sleep = execute('sleep')['progress']['date']['countDistinct']
    assert sleep == '5', sleep
    print('PASS: push-ups max=26 (65%), salary max monthly sum=15000.00, midnight rest distinct dates=5, user/deletion/type/date isolation', flush=True)
    # Keep fixture state deterministic for Java scenarios.
    runner.docker('exec', '-i', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'aio_life', sql="""
DELETE FROM time_record;
INSERT INTO time_record(id,date,category_id,user_id,start_time,end_time)
WITH RECURSIVE n AS (SELECT 1 AS v UNION ALL SELECT v+1 FROM n WHERE v<25)
SELECT CONCAT('sleep-',v),(DATE '2026-01-01' + (v-1)),1,101,0,420 FROM n;
INSERT INTO time_record(id,date,category_id,user_id,start_time,end_time) VALUES
 ('duplicate','2026-01-01',1,101,0,10),('nap','2026-01-26',1,101,780,840),
 ('late','2026-01-27',1,101,1,420),('other','2026-01-28',1,102,0,420),
 ('deleted','2026-01-29',1,101,0,420),('outside','2027-01-01',1,101,0,420);
UPDATE time_record SET is_deleted=1 WHERE id='deleted';
""")
    # 只在本脚本新建的隔离 PostgreSQL 中加载当前表定义和模拟账号。
    schema = (SERVER / 'sql/migrations/V20261110_01__postgresql_schema.sql').read_text()
    goal_sql = re.search(r'CREATE TABLE "goal" \(.*?\n \);', schema, re.S).group()
    runner.docker('exec', '-i', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'aio_life',
                  sql=goal_sql.replace(' COLLATE aio_unicode_ci', '') + "\nINSERT INTO goal(id,user_id,title,target_value,current_value) VALUES(8000,101,'migration-fixture',40,26);\nCREATE TABLE \"user\" (id BIGINT PRIMARY KEY,is_deleted SMALLINT DEFAULT 0); INSERT INTO \"user\"(id) VALUES(101),(102);")
    port = runner.docker('port', postgres, '5432').rsplit(':',1)[1]
    env = dict(os.environ, AIO_GOAL_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:' + port + '/aio_life?stringtype=unspecified', AIO_GOAL_TEST_QUERY_URL=url, AIO_GOAL_TEST_EXAMPLES=str(ROOT / 'examples'))
    with (base / 'goal-business-tests.log').open('w') as log:
        result = subprocess.run(['mvn','-q','-Dtest=GoalGraphqlIntegrationTest','test'],cwd=SERVER,env=env,stdout=log,stderr=subprocess.STDOUT)
    assert result.returncode == 0, 'Business acceptance failed: ' + str(base / 'goal-business-tests.log')
    print('PASS: Goal controller -> persisted GraphQL -> Query -> Hasura -> Connector -> PostgreSQL; see ' + str(base), flush=True)


if __name__ == '__main__':
    runner.main((ROOT / 'tests/goal-fixture.sql').read_text(), acceptance)
