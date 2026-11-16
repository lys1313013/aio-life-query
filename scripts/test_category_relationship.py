#!/usr/bin/env python3
"""Category acceptance on the same pinned runtime images used for publication."""
import json
import subprocess
from runtime_test_stack import ROOT, RuntimeStack, docker, request


def main(extra_fixture='', acceptance=None):
    fixture = (ROOT / 'tests/category-fixture.sql').read_text() + '\n' + extra_fixture
    with RuntimeStack(fixture) as stack:
        postgres, base, url = stack.postgres, stack.base, stack.url + '/graphql'
        example = json.loads((ROOT / 'examples/sleep-before-midnight.json').read_text())

        def query(body, token='user-a'):
            stack.reset_rates()
            return stack.query(body, token)

        result = query(example)
        assert [row['date'] for row in result['timeRecords']] == ['2026-10-01', '2026-10-02', '2026-10-04'], result
        assert [row['category']['id'] for row in result['timeRecords']] == ['1', '6', '9007199254740993'], result
        assert all(row['category']['name'] == '休息' for row in result['timeRecords'])
        (base / 'response.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        other = query(example, 'user-b')
        assert len(other['timeRecords']) == 1 and other['timeRecords'][0]['date'] == '2026-10-01', other
        stats = query({'query': 'query {timeRecordStats(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"},startMinute:{_eq:0},category:{name:{_eq:"休息"}}}){count}}'})
        assert stats['timeRecordStats']['count'] == '3', stats
        all_rows = query({'query': '{timeRecords(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"}},orderBy:[{date:Asc}]){id kind:category{key:id label:name}}}'})['timeRecords']
        by_id = {row['id']: row['kind'] for row in all_rows}
        assert by_id['3']['label'] == '阅读', by_id
        assert all(by_id[record] is None for record in ['5', '6', '7']), by_id
        assert '9' not in by_id and '11' not in by_id, by_id
        by_category = query({'query': 'query($id:String!){timeRecords(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"},category:{id:{_eq:$id},name:{_eq:"休息"}}}){id category{id name}}}',
                             'variables': {'id': '9007199254740993'}})['timeRecords']
        assert len(by_category) == 1 and by_category[0]['id'] == '4', by_category
        status, rejected = request(url, {'query': example['query'].replace('category { id name }', 'category { id userId }')}, 'user-a')
        assert status == 400, rejected
        denied = subprocess.run(['docker', 'exec', postgres, 'psql', '-U', 'fixture_read', '-d', 'aio_life', '-c', 'UPDATE time_record SET start_minute=1'], capture_output=True)
        assert denied.returncode != 0, 'Database account unexpectedly permits writes'
        if acceptance:
            acceptance(query, postgres, base, url)
        print('PASS: exact query, effective category names, BIGINT strings, nested aliases/nulls, aggregates, user/deletion isolation and read-only grants')
        print('Response: ' + str(base / 'response.json'))


if __name__ == '__main__':
    main()
