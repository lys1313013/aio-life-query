#!/usr/bin/env python3
"""Isolated HTTP -> gateway -> Hasura -> readonly MySQL relationship acceptance test.

Requires Docker, JDK 21, a packaged gateway and the built connector. Authentication
uses a local fixture; no real credentials or business database are accessed.
"""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('metadata', ROOT / 'scripts/build_metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def docker(*args, sql=None, check=True):
    return subprocess.run(['docker', *args], input=sql, text=True, capture_output=True, check=check).stdout.strip()


def request(url, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urlopen(req, timeout=15) as response:
            content = response.read()
            try:
                data = json.loads(content) if content else None
            except json.JSONDecodeError:
                data = content.decode()
            return response.status, data
    except HTTPError as error:
        content = error.read()
        return error.code, json.loads(content) if content else None


class AuthFixture(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        token = self.headers.get('Authorization')
        user = {'Bearer user-a': '101', 'Bearer user-b': '102'}.get(token)
        status = 200 if user else 401
        data = {'code': 0, 'data': {'userId': user, 'appId': 'aio-life-query',
            'credentialId': 'a' * 32, 'credentialType': 'login', 'expiresAt': int(time.time()) + 3600,
            'role': 'ai_time_reader', 'scopes': ['time.read']}} if user else {'code': 401}
        content = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *args):
        pass


def recording_proxy(upstream, base, prefix):
    class Proxy(BaseHTTPRequestHandler):
        def do_POST(self):
            content = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            (base / (prefix + '-request.json')).write_bytes(content)
            status, data = request(upstream + self.path, json.loads(content), self.headers.get('Authorization', '')[7:])
            (base / (prefix + '-response.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2))
            output = json.dumps(data).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(output)

        def log_message(self, *args):
            pass
    server = ThreadingHTTPServer(('0.0.0.0', 0), Proxy)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main():
    run_id = str(os.getpid())
    base = ROOT / '.cache' / ('category-e2e-' + run_id)
    base.mkdir(parents=True)
    prefix = 'aio-query-category-' + run_id
    containers, processes, logs = [], [], []
    auth = ThreadingHTTPServer(('127.0.0.1', 0), AuthFixture)
    threading.Thread(target=auth.serve_forever, daemon=True).start()
    gateway_port, connector_port, engine_port = free_port(), free_port(), free_port()
    key = 'category-fixture-secret-at-least-32-characters'
    connector_proxy = recording_proxy('http://127.0.0.1:' + str(connector_port), base, 'connector')
    engine_proxy = recording_proxy('http://127.0.0.1:' + str(engine_port), base, 'engine')

    def start_process(name, command, extra_env):
        log = (base / (name + '.log')).open('wb')
        logs.append(log)
        process = subprocess.Popen(command, cwd=ROOT, env=dict(os.environ, **extra_env), stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)

    def wait_ready(url, token=None):
        for _ in range(60):
            try:
                status, _ = request(url, token=token)
                if status == 200:
                    return
            except (URLError, OSError):
                pass
            if any(process.poll() is not None for process in processes):
                raise RuntimeError('Process exited; see ' + str(base))
            time.sleep(0.5)
        raise RuntimeError('Readiness timed out: ' + url)

    try:
        mysql, redis, engine = (prefix + suffix for suffix in ['-mysql', '-redis', '-hasura'])
        for name, image, port, options in [
            (mysql, os.environ.get('AIO_QUERY_CATEGORY_MYSQL_IMAGE', 'mysql@sha256:ac80b6e09e5b12b4f9d5cd4f6425e43464247aa4ba4f6169da5daf59e5877f7d'), '3306', ['-e', 'MYSQL_ROOT_PASSWORD=fixture-root', '-e', 'MYSQL_DATABASE=aio_life']),
            (redis, 'redis@sha256:520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7', '6379', [])
        ]:
            if name == mysql and os.environ.get('AIO_QUERY_CATEGORY_MYSQL_PLATFORM'):
                options += ['--platform', os.environ['AIO_QUERY_CATEGORY_MYSQL_PLATFORM']]
            docker('run', '-d', '--name', name, '-p', '127.0.0.1::' + port, *options, image)
            containers.append(name)
        mysql_port = docker('port', mysql, '3306').rsplit(':', 1)[1]
        redis_port = docker('port', redis, '6379').rsplit(':', 1)[1]
        for _ in range(90):
            # The initialization server accepts socket connections before setup is complete.
            ready = subprocess.run(['docker', 'exec', mysql, 'mysql', '--protocol=TCP', '-h127.0.0.1',
                                    '-uroot', '-pfixture-root', 'aio_life', '-e', 'SELECT 1'],
                                   capture_output=True).returncode == 0
            if ready:
                break
            time.sleep(0.5)
        assert ready, 'MySQL startup failed'
        fixture = (ROOT / 'tests/category-fixture.sql').read_text()
        docker('exec', '-i', mysql, 'mysql', '--default-character-set=utf8mb4', '-uroot', '-pfixture-root', 'aio_life', sql=fixture)
        config = base / 'metadata'
        bundle, schema = metadata.build('http://host.docker.internal:' + str(connector_proxy.server_port), key)
        auth_config = {'version': 'v4', 'definition': {'mode': {'webhook': {
            'url': {'value': 'http://host.docker.internal:' + str(gateway_port) + '/internal/hasura/auth'},
            'method': 'POST', 'customHeadersConfig': {'body': {'headers': {'forward': ['authorization']}},
                'headers': {'additional': {'X-AIO-Webhook-Key': key}}}}}}}
        metadata.write_bundle(config, [('open_dd.json', bundle), ('auth_config.json', auth_config),
                                      ('configuration.json', metadata.connector_config()), ('ndc-schema.json', schema)])
        start_process('connector', ['java', '-jar', str(ROOT / '.cache/connector/quarkus-app/quarkus-run.jar')], {
            'HASURA_CONFIGURATION_DIRECTORY': str(config), 'HASURA_SERVICE_TOKEN_SECRET': key,
            'JDBC_URL': 'jdbc:mysql://127.0.0.1:' + mysql_port + '/aio_life?user=fixture_read&password=fixture-read&useSSL=false&allowPublicKeyRetrieval=true',
            'QUARKUS_HTTP_HOST': '0.0.0.0', 'QUARKUS_HTTP_PORT': str(connector_port),
            'QUARKUS_OTEL_SDK_DISABLED': 'true', 'QUARKUS_HTTP_ACCESS_LOG_ENABLED': 'false'})
        wait_ready('http://127.0.0.1:' + str(connector_port) + '/capabilities', key)
        docker('run', '-d', '--name', engine, '--add-host=host.docker.internal:host-gateway',
            '-p', '127.0.0.1:' + str(engine_port) + ':3000', '-v', str(config) + ':/metadata:ro',
            'ghcr.io/hasura/v3-engine@sha256:7f30c6697a3921eb936002b44a32ab01efdd892a8b9af236cad2f81e0a1b1d91',
            '--metadata-path', '/metadata/open_dd.json', '--authn-config-path', '/metadata/auth_config.json',
            '--expose-internal-errors')
        containers.append(engine)
        wait_ready('http://127.0.0.1:' + str(engine_port) + '/health')
        start_process('gateway', ['java', '-jar', str(ROOT / 'target/aio-life-query-0.1.0-SNAPSHOT.jar')], {
            'AIO_QUERY_PORT': str(gateway_port), 'AIO_QUERY_MANAGEMENT_PORT': '0',
            'AIO_QUERY_SERVICE_KEY': key, 'AIO_QUERY_WEBHOOK_KEY': key,
            'AIO_QUERY_ACCESS_URL': 'http://127.0.0.1:' + str(auth.server_port) + '/check-token',
            'AIO_QUERY_HASURA_URL': 'http://127.0.0.1:' + str(engine_proxy.server_port) + '/graphql',
            'AIO_QUERY_REDIS_HOST': '127.0.0.1', 'AIO_QUERY_REDIS_PORT': redis_port,
            'AIO_QUERY_REDIS_PASSWORD': '', 'AIO_QUERY_AUDIT_DIRECTORY': str(base / 'audit')})
        url = 'http://127.0.0.1:' + str(gateway_port) + '/graphql'
        for _ in range(60):
            try:
                if request(url, {'query': '{timeRecords{id}}'})[0] == 401:
                    break
            except (URLError, OSError):
                pass
            time.sleep(0.5)
        example = json.loads((ROOT / 'examples/sleep-before-midnight.json').read_text())

        def query(body, token='user-a'):
            status, data = request(url, body, token)
            assert status == 200 and 'errors' not in data, (status, data)
            return data['data']

        result = query(example)
        assert [row['date'] for row in result['timeRecords']] == ['2026-10-01', '2026-10-02', '2026-10-04'], result
        assert [row['category']['id'] for row in result['timeRecords']] == ['1', '6', '9007199254740993'], result
        assert all(row['category']['name'] == '休息' for row in result['timeRecords'])
        (base / 'response.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        other = query(example, 'user-b')
        assert len(other['timeRecords']) == 1 and other['timeRecords'][0]['date'] == '2026-10-01', other
        stats = query({'query': 'query {timeRecordStats(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"},startTime:{_eq:0},category:{name:{_eq:"休息"}}}){count}}'})
        assert stats['timeRecordStats']['count'] == '3', stats
        all_rows = query({'query': '{timeRecords(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"}},orderBy:[{date:Asc}]){id kind:category{key:id label:name}}}'})['timeRecords']
        by_id = {row['id']: row['kind'] for row in all_rows}
        assert by_id['a3']['label'] == '阅读', by_id
        assert all(by_id[record] is None for record in ['a5', 'a6', 'a7']), by_id
        assert 'deleted-record' not in by_id and 'other-user' not in by_id, by_id
        by_category = query({'query': 'query($id:String!){timeRecords(where:{date:{_gte:"2026-10-01",_lt:"2026-11-01"},category:{id:{_eq:$id},name:{_eq:"休息"}}}){id category{id name}}}',
                             'variables': {'id': '9007199254740993'}})['timeRecords']
        assert len(by_category) == 1 and by_category[0]['id'] == 'a4', by_category
        status, rejected = request(url, {'query': example['query'].replace('category { id name }', 'category { id userId }')}, 'user-a')
        assert status == 400, rejected
        denied = subprocess.run(['docker', 'exec', mysql, 'mysql', '-ufixture_read', '-pfixture-read', 'aio_life', '-e', 'UPDATE time_record SET start_time=1'], capture_output=True)
        assert denied.returncode != 0, 'Database account unexpectedly permits writes'
        print('PASS: exact query, effective category names, BIGINT strings, nested aliases/nulls, aggregates, user/deletion isolation and read-only grants')
        print('Response: ' + str(base / 'response.json'))
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for name in reversed(containers):
            output = subprocess.run(['docker', 'logs', name], capture_output=True, text=True)
            (base / (name + '.log')).write_text(output.stdout + output.stderr)
            docker('rm', '-f', name, check=False)
        for log in logs:
            log.close()
        auth.shutdown()
        connector_proxy.shutdown()
        engine_proxy.shutdown()


if __name__ == '__main__':
    main()
