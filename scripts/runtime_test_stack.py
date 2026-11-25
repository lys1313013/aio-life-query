"""Isolated runtime assembly shared by release and business-query acceptance tests."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('runtime_metadata', ROOT / 'scripts/build_metadata.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


def docker(*args, sql=None):
    return subprocess.run(['docker', *args], input=sql, text=True, capture_output=True, check=True).stdout.strip()


def request(url, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(url, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urlopen(req, timeout=15) as response:
            content = response.read()
            try:
                body = json.loads(content) if content else None
            except ValueError:
                body = content.decode(errors='replace')
            return response.status, body
    except HTTPError as error:
        content = error.read()
        try:
            body = json.loads(content)
        except ValueError:
            body = content.decode(errors='replace')
        return error.code, body


def free_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


class RuntimeStack:
    def __init__(self, fixture_sql, users=('101', '102')):
        home = os.environ.get('AIO_QUERY_JAVA_HOME') or os.environ.get('JAVA_HOME')
        self.java = str(Path(home) / 'bin/java') if home else shutil.which('java')
        version = subprocess.run([self.java, '-version'], capture_output=True, text=True)
        assert re.search(r'version "21\.', version.stdout + version.stderr), 'JDK 21 is required; set AIO_QUERY_JAVA_HOME or JAVA_HOME'
        self.prefix = 'aio-query-runtime-' + uuid.uuid4().hex[:12]
        self.base = ROOT / '.cache' / self.prefix
        self.base.mkdir(parents=True)
        self.fixture_sql = fixture_sql
        self.key = 'runtime-fixture-secret-at-least-32-characters'
        self.gateway_port = free_port()
        self.users = tuple(users)
        self.active_tokens = dict(zip(('user-a', 'user-b'), self.users))
        self.locked = set()
        self.containers = []
        self.process = None
        self.auth = None
        self.network_created = False
        self.postgres = self.prefix + '-postgres'
        self.redis = self.prefix + '-redis'
        self.connector = self.prefix + '-connector'
        self.engine = self.prefix + '-engine'

    def wait(self, check, description):
        for _ in range(120):
            try:
                if check():
                    return
            except (OSError, URLError, ValueError, subprocess.CalledProcessError):
                pass
            if self.containers:
                states = docker('inspect', '--format', '{{.State.Status}}', *self.containers).splitlines()
                if any(state in ['exited', 'dead'] for state in states):
                    raise RuntimeError('Runtime container exited; see ' + str(self.base))
            if self.process is not None and self.process.poll() is not None:
                raise RuntimeError('Gateway exited; see ' + str(self.base))
            time.sleep(0.5)
        raise RuntimeError('Readiness timed out: ' + description + '; see ' + str(self.base))

    def __enter__(self):
        stack = self

        class AuthFixture(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
                token = self.headers.get('Authorization', '')[7:]
                user = stack.active_tokens.get(token)
                name = body.get('dataset', 'time_record')
                dataset = next((d for d in metadata.DATASETS if d['name'] == name), None)
                if not user:
                    status, output = 401, {'code': 401, 'message': 'fixture credential rejected'}
                elif (token, name) in stack.locked:
                    status, output = 200, {'code': 2001, 'data': {'menuPath': '/query/workbench'}}
                else:
                    role, scope = (dataset['role'], dataset['scope']) if dataset else ('ai_query_catalog', 'query.catalog')
                    status, output = 200, {'code': 0, 'data': {'userId': user, 'appId': 'aio-life-query',
                        'credentialId': 'a' * 32, 'credentialType': 'login', 'expiresAt': int(time.time()) + 3600,
                        'role': role, 'scopes': [scope]}}
                    if name == 'catalog':
                        output['data']['allowedDatasets'] = [
                            item['name'] for item in metadata.DATASETS
                            if (token, item['name']) not in stack.locked
                        ]
                content = json.dumps(output).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(content)))
                self.end_headers()
                self.wfile.write(content)

            def log_message(self, *_):
                pass

        try:
            self.auth = ThreadingHTTPServer(('127.0.0.1', 0), AuthFixture)
            threading.Thread(target=self.auth.serve_forever, daemon=True).start()
            docker('network', 'create', self.prefix)
            self.network_created = True
            for name, image, alias, port, extra in [
                (self.postgres, os.environ.get('AIO_QUERY_TEST_POSTGRES_IMAGE', 'postgres:18-alpine'), 'postgres', '5432',
                 ['-e', 'POSTGRES_PASSWORD=fixture-root', '-e', 'POSTGRES_DB=aio_life']),
                (self.redis, 'redis:7-alpine', 'redis', '6379', [])
            ]:
                docker('run', '-d', '--name', name, '--network', self.prefix, '--network-alias', alias,
                       '-p', '127.0.0.1::' + port, *extra, image)
                self.containers.append(name)
            self.wait(lambda: subprocess.run(['docker', 'exec', '-e', 'PGPASSWORD=fixture-root', self.postgres, 'psql', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'aio_life', '-Atc', 'SELECT 1'],
                                             capture_output=True).returncode == 0, 'PostgreSQL')
            docker('exec', '-i', self.postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'aio_life', sql=self.fixture_sql)
            config = self.base / 'metadata'
            bundle, schema = metadata.build('http://postgresql-connector:8080', self.key)
            auth = {'version':'v4','definition':{'mode':{'webhook':{
                'url':{'value':'http://host.docker.internal:' + str(self.gateway_port) + '/internal/hasura/auth'},
                'method':'POST','customHeadersConfig':{'body':{'headers':{'forward':['authorization']}},
                'headers':{'additional':{'X-AIO-Webhook-Key':self.key}}}}}}}
            metadata.write_bundle(config, [('open_dd.json', bundle), ('auth_config.json', auth),
                                          ('configuration.json', metadata.connector_config()), ('ndc-schema.json', schema)])
            self.config = config
            connector_image = os.environ.get('AIO_QUERY_CONNECTOR_IMAGE', 'aio-life-query-postgresql-connector:local')
            docker('run', '-d', '--name', self.connector, '--network', self.prefix, '--network-alias', 'postgresql-connector',
                   '--group-add', str(os.getgid()), '-p', '127.0.0.1::8080', '-v', str(config) + ':/etc/connector:ro',
                   '-e', 'HASURA_SERVICE_TOKEN_SECRET=' + self.key,
                   '-e', 'CONNECTION_URI=postgresql://fixture_read:fixture-read@postgres:5432/aio_life', connector_image)
            self.containers.append(self.connector)
            self.connector_url = 'http://127.0.0.1:' + docker('port', self.connector, '8080').rsplit(':', 1)[1]
            self.wait(lambda: request(self.connector_url + '/schema', token=self.key)[0] == 200, 'PostgreSQL connector')
            engine_image = os.environ.get('AIO_QUERY_ENGINE_IMAGE', 'aio-life-query-hasura:local')
            docker('run', '-d', '--name', self.engine, '--network', self.prefix, '--network-alias', 'hasura',
                   '--add-host=host.docker.internal:host-gateway', '--group-add', str(os.getgid()),
                   '-p', '127.0.0.1::3000', '-v', str(config) + ':/metadata:ro', engine_image,
                   '--metadata-path', '/metadata/open_dd.json', '--authn-config-path', '/metadata/auth_config.json',
                   '--ndc-response-size-limit', '1000000', '--request-body-limit', '65536')
            self.containers.append(self.engine)
            self.engine_url = 'http://127.0.0.1:' + docker('port', self.engine, '3000').rsplit(':', 1)[1]
            self.wait(lambda: request(self.engine_url + '/health')[0] == 200, 'Hasura release engine')
            redis_port = docker('port', self.redis, '6379').rsplit(':', 1)[1]
            candidates = sorted((ROOT / 'target').glob('aio-life-query-*.jar'))
            assert len(candidates) == 1, 'Build one tested gateway JAR first'
            environment = dict(os.environ, AIO_QUERY_PORT=str(self.gateway_port), AIO_QUERY_MANAGEMENT_PORT='0',
                AIO_QUERY_ACCESS_URL='http://127.0.0.1:' + str(self.auth.server_port) + '/check-token',
                AIO_QUERY_HASURA_URL=self.engine_url + '/graphql', AIO_QUERY_SERVICE_KEY=self.key,
                AIO_QUERY_WEBHOOK_KEY=self.key, AIO_QUERY_REDIS_HOST='127.0.0.1', AIO_QUERY_REDIS_PORT=redis_port,
                AIO_QUERY_REDIS_PASSWORD='', AIO_QUERY_AUDIT_DIRECTORY=str(self.base / 'audit'))
            self.gateway_log = (self.base / 'gateway.log').open('wb')
            self.process = subprocess.Popen([self.java, '-jar', str(candidates[0])], cwd=ROOT, env=environment,
                                            stdout=self.gateway_log, stderr=subprocess.STDOUT)
            self.url = 'http://127.0.0.1:' + str(self.gateway_port)
            self.wait(lambda: request(self.url + '/api/v1/datasets')[0] == 401, 'Query gateway')
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def reset_rates(self):
        # Isolated fixture only: advance the request quota between test groups.
        docker('exec', self.redis, 'redis-cli', 'DEL', *('aioQuery:rate:v1:' + user for user in self.users))

    def query(self, body, token='user-a'):
        status, data = request(self.url + '/graphql', body, token)
        assert status == 200 and 'errors' not in data, (status, data)
        return data['data']

    def __exit__(self, *_):
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            self.gateway_log.close()
        for name in reversed(self.containers):
            logs = subprocess.run(['docker', 'logs', name], capture_output=True, text=True)
            (self.base / (name + '.log')).write_text(logs.stdout + logs.stderr)
            subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
        if self.network_created:
            subprocess.run(['docker', 'network', 'rm', self.prefix], capture_output=True)
        if self.auth is not None:
            self.auth.shutdown()
