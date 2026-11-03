"""Repeatable regression entry: quick (no infrastructure) or fresh middleware.

Never reuses or removes existing containers/data. External AI is a separate,
explicitly frozen evaluation; these tests use local supplier fixtures only.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def run(args, *, env=None, stdin=None, log=None, check=True):
    result = subprocess.run(args, cwd=ROOT, env=env, input=stdin, capture_output=True,
                            text=True, encoding='utf-8', errors='replace',
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if log:
        Path(log).write_text(result.stdout + result.stderr, encoding='utf-8')
    if check and result.returncode:
        raise RuntimeError(f'{Path(str(args[0])).name} failed ({result.returncode}); see private log')
    return result


def reports(folder):
    tests = []
    for path in sorted(folder.glob('TEST-*.xml')):
        for case in ET.parse(path).getroot().findall('testcase'):
            status = ('SKIP' if case.find('skipped') is not None else
                      'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'PASS')
            tests.append(dict(name=case.get('classname') + '.' + case.get('name'), status=status))
    return dict(total=len(tests), **{s: sum(t['status'] == s for t in tests) for s in ['PASS', 'FAIL', 'SKIP']}, cases=tests)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['quick', 'middleware'])
    args = parser.parse_args()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + secrets.token_hex(3)
    folder = ROOT / '.local-validation/regression' / stamp
    folder.mkdir(parents=True)
    public = ROOT / 'docs/evidence/product-portfolio/regression' / (stamp + '.json')
    public.parent.mkdir(parents=True, exist_ok=True)
    result = dict(id=stamp, mode=args.mode, status='RUNNING', external_model_calls=0,
                  supplier='local protocol fixtures', containers=[], groups=[])
    env = os.environ.copy()
    # Do not let a developer's prior opt-in integration env silently affect quick tests.
    for key in list(env):
        if re.match(r'PHASE\d+_', key):
            del env[key]
    env.update(SPRING_CONFIG_IMPORT='', AI_ENABLED='false', AUTOMATION_ENABLED='false',
               KNOWLEDGE_EXTERNAL_CALLS_ENABLED='false', KNOWLEDGE_JOBS_ENABLED='false',
               CONTROLLED_DISPATCH_REQUIRED='false', EMAIL_ENABLED='false')
    maven = shutil.which('mvn') or shutil.which('mvn.cmd')
    if not maven:
        raise SystemExit('Maven is required')
    containers = []
    def mvn(label, selectors=None):
        build = folder / label
        command = [maven, '-q', '-f', 'backend/pom.xml', f'-Dprinter.build.directory={build.as_posix()}']
        if selectors:
            command.append('-Dtest=' + selectors)
        command.append('test')
        completed = run(command, env=env, log=folder / (label + '.log'), check=False)
        group = dict(name=label, exit_code=completed.returncode, **reports(build / 'surefire-reports'))
        result['groups'].append(group)
        print(json.dumps({k: v for k, v in group.items() if k != 'cases'}), flush=True)
        if completed.returncode or not group['total'] or group['FAIL']:
            raise RuntimeError(label + ' failed; logs retained')
        if args.mode == 'middleware' and group['SKIP']:
            raise RuntimeError(label + ' unexpectedly skipped integration checks')
    try:
        if args.mode == 'quick':
            mvn('quick')
            result['gate_contract'] = dict(status='NOT_DISTRIBUTED_PRIVATE_EVALUATION', reason='Historical evaluation gate requires non-redistributable corpus; not part of public quick scope')
            completed = run([shutil.which('python') or 'python', '-X', 'utf8', '-m', 'unittest',
                             'discover', '-s', 'scripts', '-p', 'test_portfolio_runtime.py'],
                            log=folder / 'runtime.log', check=False)
            result['runtime_contract'] = dict(exit_code=completed.returncode, status='PASS' if completed.returncode == 0 else 'FAIL')
            if completed.returncode:
                raise RuntimeError('Runtime contract regression failed')
        else:
            run(['docker', 'info'], log=folder / 'docker-info.log')
            compose = (ROOT / 'deploy/linux/compose.yaml').read_text('utf-8')
            images = {name: re.search(r'\n  ' + name + r':\n    image: ([^\n]+)', compose).group(1).strip()
                      for name in ['mysql', 'redis', 'rabbitmq', 'qdrant']}
            suffix = secrets.token_hex(6)
            root_password, password = secrets.token_hex(24), secrets.token_hex(24)
            names = dict(mysql='printer-reg-mysql-' + suffix, redis='printer-p5-redis-' + suffix,
                         rabbitmq='phase3-broker-' + suffix, qdrant='printer-p4-qdrant-' + suffix)
            config = dict(root_password=root_password, password=password, names=names)
            (folder / 'private.json').write_text(json.dumps(config), 'utf-8')
            for service, internal in [('mysql', 3306), ('redis', 6379), ('rabbitmq', 5672), ('qdrant', 6333)]:
                # Explicit host binding survives docker stop/start fault injection.
                # Docker-assigned ephemeral bindings may change after restart.
                with socket.socket() as reservation:
                    reservation.bind(('127.0.0.1', 0))
                    host_port = reservation.getsockname()[1]
                command = ['docker', 'run', '-d', '--name', names[service], '--label', 'printer.regression=' + stamp,
                           '-p', f'127.0.0.1:{host_port}:{internal}']
                docker_env = env.copy()
                if service == 'mysql':
                    docker_env['MYSQL_ROOT_PASSWORD'] = root_password
                    command += ['-e', 'MYSQL_ROOT_PASSWORD']
                if service == 'rabbitmq':
                    docker_env.update(RABBITMQ_DEFAULT_USER='printer', RABBITMQ_DEFAULT_PASS=password)
                    command += ['-e', 'RABBITMQ_DEFAULT_USER', '-e', 'RABBITMQ_DEFAULT_PASS']
                if service == 'qdrant':
                    command += ['-e', 'QDRANT__TELEMETRY_DISABLED=true', '--label', 'local.validation=product-phase4-qdrant']
                command.append(images[service])
                run(command, env=docker_env, log=folder / (service + '-create.log'))
                containers.append(names[service])
            ports = {service: int(run(['docker', 'port', name, str(internal)]).stdout.strip().rsplit(':', 1)[1])
                     for (service, internal), name in zip([('mysql', 3306), ('redis', 6379), ('rabbitmq', 5672), ('qdrant', 6333)], names.values())}
            config['ports'] = ports
            (folder / 'private.json').write_text(json.dumps(config), 'utf-8')
            sql_env = env.copy(); sql_env['MYSQL_PWD'] = root_password
            def sql(statement, check=True):
                return run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', names['mysql'], 'mysql', '-uroot'],
                           env=sql_env, stdin=statement, check=check)
            deadline = time.monotonic() + 180
            while sql('SELECT 1;', False).returncode:
                if time.monotonic() > deadline:
                    raise RuntimeError('Fresh MySQL readiness timeout')
                time.sleep(2)
            schemas = {3: 'printer_p3_broker_phase3_' + suffix, 4: 'printer_p4_' + suffix,
                       5: 'printer_p5_' + suffix, 9: 'printer_p9_' + suffix}
            sql("CREATE USER 'printer'@'%' IDENTIFIED BY '" + password + "';" + ''.join(
                f'CREATE DATABASE {schema} CHARACTER SET utf8mb4; GRANT ALL ON {schema}.* TO \'printer\'@\'%\';'
                for schema in schemas.values()))
            for phase, schema in schemas.items():
                env.update({f'PHASE{phase}_DB_URL': f'jdbc:mysql://127.0.0.1:{ports["mysql"]}/{schema}?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8',
                            f'PHASE{phase}_DB_USER': 'printer', f'PHASE{phase}_DB_PASSWORD': password})
            env.update(PHASE4_QDRANT_URL=f'http://127.0.0.1:{ports["qdrant"]}', PHASE4_LOCAL=str(folder),
                       PHASE4_QDRANT_CONTAINER=names['qdrant'],
                       PHASE5_REDIS_PORT=str(ports['redis']), PHASE5_REDIS_CONTAINER=names['redis'],
                       PHASE3_RABBIT_CONTAINER=names['rabbitmq'], RABBITMQ_HOST='127.0.0.1',
                       RABBITMQ_PORT=str(ports['rabbitmq']), RABBITMQ_USER='printer', RABBITMQ_PASSWORD=password,
                       PHASE3_RABBIT_PORT=str(ports['rabbitmq']), PHASE3_RABBIT_USER='printer', PHASE3_RABBIT_PASSWORD=password)
            # The broker test migrates phase3 first; the following DB-only tests use that fresh schema.
            deadline = time.monotonic() + 120
            while run(['docker', 'exec', names['rabbitmq'], 'rabbitmq-diagnostics', '-q', 'check_running'], check=False).returncode:
                if time.monotonic() > deadline:
                    raise RuntimeError('Fresh RabbitMQ readiness timeout')
                time.sleep(2)
            mvn('broker', 'KnowledgeJobDatabaseBrokerIT')
            mvn('middleware', 'StoreDatabaseTest,KnowledgeIndexPipelineDatabaseTest,HybridKnowledgeDatabaseTest,Phase5ApiTest,RedisProtectionTest,ExternalCallBudgetTest')
            # The cache suite inherits the nine hybrid scenarios and re-runs them with
            # Redis enabled. Its fixed rule document key requires its own schema.
            cache_schema = 'printer_p4_cache_' + suffix
            sql(f"CREATE DATABASE {cache_schema} CHARACTER SET utf8mb4; GRANT ALL ON {cache_schema}.* TO 'printer'@'%';")
            env['PHASE4_DB_URL'] = env['PHASE4_DB_URL'].replace(schemas[4], cache_schema)
            mvn('candidate-cache', 'Phase5CandidateCacheTest')
            result['overlap_note'] = 'Candidate-cache inherits 9 hybrid cases, executed under Redis-enabled config; group totals are executions, not unique business scenarios.'
        result['status'] = 'PASS'
    except Exception as exc:
        result['status'] = 'FAIL'
        result['failure'] = str(exc)
        raise
    finally:
        for name in containers:
            inspected = run(['docker', 'inspect', '--format', '{{index .Config.Labels "printer.regression"}}', name], check=False)
            if inspected.returncode == 0 and inspected.stdout.strip() == stamp:
                stopped = run(['docker', 'stop', '-t', '3', name], check=False)
                result['containers'].append(dict(name=name, stopped=stopped.returncode == 0, data='PRESERVED'))
        result['finished_at'] = datetime.now(timezone.utc).isoformat()
        public.write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        print(str(public), flush=True)


if __name__ == '__main__':
    main()
