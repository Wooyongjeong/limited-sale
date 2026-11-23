"""Disposable, explicitly owned infrastructure for the regression runner."""
import base64
import contextlib
from datetime import datetime, timezone
import hashlib
import json
import os
import platform
import resource
import sys
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

from redact import redact_line
from service_env import inherited_environment

ROOT = Path(__file__).resolve().parents[2]
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java')


def command(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, **kwargs).stdout.strip()


def file_hash(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def reserve_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


LB_IMAGE = 'caddy:2.11.2-alpine'
LB_UPSTREAM_HOST = 'host.docker.internal'


class Isolated:
    def __init__(self, output, instances=('sales1', 'sales2'), load_balancer=False):
        self.instances = tuple(instances)
        self.load_balancer = load_balancer
        self.started_at = datetime.now(timezone.utc).isoformat()
        self.source_sha256 = {p.name: file_hash(p) for p in sorted((ROOT / 'tools/order-regression').iterdir())
                              if p.is_file() and p.suffix in ('.py', '.java')}
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=False)
        self.run_id = 'ls175-' + uuid.uuid4().hex[:12]
        self.secret = secrets.token_hex(20)
        self.password = secrets.token_hex(20)
        self.containers = []
        self.volumes = []
        self.processes = []
        self.logs = []
        self.log_threads = []
        self.ports = {}
        signal.signal(signal.SIGTERM, self._interrupt)

    @staticmethod
    def _interrupt(_signum, _frame):
        raise KeyboardInterrupt()

    def container(self, suffix, image, internal_port, env=None, options=(), args=()):
        name = self.run_id + '-' + suffix
        # Pass ephemeral credentials through a private temporary env file, never argv or logs.
        with tempfile.NamedTemporaryFile(mode='w', dir=self.output, delete=False) as envfile:
            os.chmod(envfile.name, 0o600)
            for key, value in (env or {}).items():
                envfile.write(f'{key}={value}\n')
            envfile.flush()
            try:
                cid = command('docker', 'run', '-d', '--name', name, '--label', f'run={self.run_id}',
                              '--env-file', envfile.name, '-p', f'127.0.0.1::{internal_port}', *options, image, *args)
                self.containers.append(cid)
                info = json.loads(command('docker', 'inspect', cid))[0]
                self.volumes.extend(item['Name'] for item in info.get('Mounts', []) if item['Type'] == 'volume')
            finally:
                os.unlink(envfile.name)
        mapping = command('docker', 'port', cid, str(internal_port) + '/tcp')
        self.ports[suffix] = int(mapping.rsplit(':', 1)[1])
        return cid

    def mysql(self, which, sql):
        cid = self.containers[0 if which == 'sales' else 1]
        # MYSQL_PWD is already present in the container; no password in the command line.
        return command('docker', 'exec', '-i', cid, 'sh', '-c',
                       'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names',
                       input=sql)

    def wait_mysql(self, which):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                if self.mysql(which, 'SELECT 1;') == '1':
                    return
            except subprocess.CalledProcessError:
                pass
            time.sleep(1)
        raise TimeoutError(f'{which} MySQL did not start')

    def start_process(self, name, args, env):
        log = open(self.output / f'{name}.log', 'w', encoding='utf-8')
        self.logs.append(log)
        child = subprocess.Popen(args, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, errors='replace', start_new_session=True)
        secrets_to_redact = (self.secret, self.password, env.get('SPRING_SECURITY_USER_PASSWORD'))
        def copy_output():
            for line in child.stdout:
                log.write(redact_line(line, secrets_to_redact))
                log.flush()
            child.stdout.close()
        thread = threading.Thread(target=copy_output, daemon=True)
        thread.start()
        self.log_threads.append(thread)
        self.processes.append(child)
        return child

    def health(self, port, child):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if child is not None and child.poll() is not None:
                raise RuntimeError(f'process {child.pid} exited {child.returncode}')
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=1) as response:
                    if json.load(response)['status'] == 'UP':
                        return
            except (OSError, ValueError, KeyError):
                time.sleep(1)
        raise TimeoutError(f'process {child.pid} health timeout')

    def start(self):
        for suffix, database in [('sales', 'limited_sale'), ('pgdb', 'mock_pg')]:
            self.container(suffix, 'mysql:8.4.7', 3306,
                           {'MYSQL_DATABASE': database, 'MYSQL_ROOT_PASSWORD': self.password})
        # Same memory limit and eviction policy as compose.yaml so experiments run against the deployed shape.
        self.container('redis', 'redis:7.4-alpine', 6379,
                       args=('--maxmemory', '256mb', '--maxmemory-policy', 'noeviction'))
        self.wait_mysql('sales')
        self.wait_mysql('pg')
        # Each sales instance needs its own management port; the default is fixed and would collide.
        self.ports.update({name: reserve_port() for name in
                           ('pg', *self.instances, *(f'{name}_management' for name in self.instances))})
        env = inherited_environment(os.environ)
        env.update({'MYSQL_PASSWORD': self.password, 'MYSQL_USER': 'root', 'MYSQL_DATABASE': 'limited_sale',
                    'MYSQL_PORT': str(self.ports['sales']), 'REDIS_PORT': str(self.ports['redis']),
                    'SPRING_SECURITY_USER_NAME': 'regression-unused',
                    'SPRING_SECURITY_USER_PASSWORD': secrets.token_hex(24),
                    'MOCK_PG_API_KEY': self.secret, 'MOCK_PG_BASE_URL': f"http://127.0.0.1:{self.ports['pg']}"})
        pg_env = env | {'MYSQL_PORT': str(self.ports['pgdb']), 'MOCK_PG_USER': 'root',
                        'MOCK_PG_PASSWORD': self.password, 'MOCK_PG_DATABASE': 'mock_pg',
                        'MOCK_PG_PORT': str(self.ports['pg'])}
        self.pg_env = pg_env
        pg = self.start_pg('pg')
        self.health(self.ports['pg'], pg)
        jar = str(ROOT / 'build/libs/limited-sale-0.0.1-SNAPSHOT.jar')
        self.sales_env = env
        self.sales_jar = jar
        for name in self.instances:
            instance = self.start_sales(name)
            self.health(self.ports[name], instance)
        if self.load_balancer:
            self.start_load_balancer()
        admin_env = env | {'ADMIN_EMAIL': 'admin@example.test', 'ADMIN_PASSWORD': self.password,
                           'ADMIN_NICKNAME': 'admin'}
        admin = self.start_process('admin-init', [JAVA, '-Dloader.main=com.wooyongjeong.limitedsale.member.infrastructure.AdminInitializer',
                                   '-cp', jar, 'org.springframework.boot.loader.launch.PropertiesLauncher'], admin_env)
        if admin.wait(timeout=120):
            raise RuntimeError('admin initializer failed; see admin-init.log')
        java_version = subprocess.run([JAVA, '-version'], text=True, capture_output=True, check=True).stderr.splitlines()[0]
        mounts = {}
        for cid in self.containers:
            inspection = json.loads(command('docker', 'inspect', cid))[0]
            mounts[cid] = [{'type': item['Type'], 'name': item.get('Name')} for item in inspection.get('Mounts', [])]
        metadata = {'source_sha256': self.source_sha256, 'mounts': mounts, 'started_at': self.started_at, 'run_id': self.run_id, 'instances': list(self.instances), 'load_balancer': self.load_balancer, 'containers': self.containers, 'ports': self.ports,
                    'os': platform.platform(), 'cpu_count': os.cpu_count(), 'python_version': sys.version.split()[0],
                    'cpu_model': command('sysctl', '-n', 'machdep.cpu.brand_string') if platform.system() == 'Darwin' else platform.processor(),
                    'open_file_limit': list(resource.getrlimit(resource.RLIMIT_NOFILE)),
                    'memory_bytes': int(command('sysctl', '-n', 'hw.memsize')) if platform.system() == 'Darwin' else os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES'),
                    'java_version': java_version, 'docker_version': command('docker', 'version', '--format', '{{.Server.Version}}'),
                    'mysql_version': command('docker', 'exec', self.containers[0], 'mysql', '--version'),
                    'redis_version': command('docker', 'exec', self.containers[2], 'redis-server', '--version'),
                    'defaults': {'lease': '30s', 'retry': '10s', 'expiry': '5m', 'recovery_deadline': '120s'},
                    'local_resources_shared': True,
                    'jar_sha256': {label: file_hash(path) for label, path in
                                   [('sales', ROOT / 'build/libs/limited-sale-0.0.1-SNAPSHOT.jar'),
                                    ('pg', ROOT / 'mock-pg/build/libs/mock-pg-0.0.1-SNAPSHOT.jar')]},
                    'pids': {name: process.pid for name, process in zip(('pg', *self.instances, 'admin'), self.processes)},
                    'head': command('git', 'rev-parse', 'HEAD'),
                    'dirty': bool(command('git', 'status', '--porcelain'))}
        (self.output / 'resources.json').write_text(json.dumps(metadata, indent=2) + '\n')
        return self

    def upstreams(self):
        return [f'{LB_UPSTREAM_HOST}:{self.ports[name]}' for name in self.instances]

    def start_load_balancer(self):
        """Run the repository Caddyfile in an owned container in front of the sales instances."""
        caddyfile = base64.b64encode((ROOT / 'infra/caddy/Caddyfile').read_bytes()).decode()
        script = ('echo "$CADDYFILE_B64" | base64 -d > /tmp/Caddyfile && '
                  'exec caddy run --config /tmp/Caddyfile --adapter caddyfile')
        self.lb_container = self.container(
            'lb', LB_IMAGE, 8000, {'CADDYFILE_B64': caddyfile, 'SALES_UPSTREAMS': ' '.join(self.upstreams())},
            options=('--add-host', f'{LB_UPSTREAM_HOST}:host-gateway'), args=('sh', '-c', script))
        # A proxied health response proves Caddy parsed the file and reached an instance.
        self.health(self.ports['lb'], None)

    def load_balancer_logs(self):
        """Return the access log on stdout and Caddy's own messages on stderr."""
        logs = subprocess.run(['docker', 'logs', self.lb_container], text=True, capture_output=True, check=True)
        return logs.stdout, logs.stderr

    def start_sales(self, name, log_name=None):
        env = self.sales_env | {'SERVER_PORT': str(self.ports[name]),
                                'MANAGEMENT_SERVER_PORT': str(self.ports[name + '_management'])}
        if hasattr(self, 'sales_proxy'):
            env['MOCK_PG_BASE_URL'] = self.sales_proxy
        if name == 'sales1' and hasattr(self, 'sales1_proxy'):
            env['MOCK_PG_BASE_URL'] = self.sales1_proxy
        return self.start_process(log_name or name, [JAVA, '-jar', self.sales_jar], env)

    def start_pg(self, name):
        pg = self.start_process(name, [JAVA, '-jar', str(ROOT / 'mock-pg/build/libs/mock-pg-0.0.1-SNAPSHOT.jar')], self.pg_env)
        return pg

    def close(self):
        for child in reversed(self.processes):
            if child.poll() is None:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    with contextlib.suppress(ProcessLookupError):
                        os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
        for thread in self.log_threads:
            thread.join(timeout=10)
        for log in self.logs:
            log.close()
        # A Docker client interrupted after creation may not have returned its ID yet.
        # The random run label limits discovery to this invocation's containers.
        found = subprocess.run(['docker', 'ps', '-aq', '--no-trunc', '--filter', f'label=run={self.run_id}'],
                               text=True, capture_output=True)
        owned = list(dict.fromkeys(self.containers + found.stdout.splitlines()))
        for cid in reversed(owned):
            subprocess.run(['docker', 'rm', '-f', '-v', cid], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        remaining = subprocess.run(['docker', 'ps', '-aq', '--no-trunc', '--filter', f'label=run={self.run_id}'],
                                   text=True, capture_output=True)
        alive = [p.pid for p in self.processes if p.poll() is None]
        remaining_volumes = [name for name in self.volumes if subprocess.run(
            ['docker', 'volume', 'inspect', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0]
        evidence = {'finished_at': datetime.now(timezone.utc).isoformat(), 'containers_owned': owned, 'remaining_containers': remaining.stdout.splitlines(),
                    'alive_pids': alive, 'remaining_volumes': remaining_volumes, 'docker_query_exit': remaining.returncode}
        (self.output / 'cleanup.json').write_text(json.dumps(evidence, indent=2) + '\n')
        if remaining.returncode or evidence['remaining_containers'] or remaining_volumes or alive:
            raise RuntimeError('owned resource cleanup incomplete; see cleanup.json')
