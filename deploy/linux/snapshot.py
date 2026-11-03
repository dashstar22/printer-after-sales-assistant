"""Quiesced, encrypted restic backup/restore. Explicit project and empty restore target required.

Uses no delete/drop/down operation. A backup intentionally leaves writers stopped.
Caller reviews recovery gates before starting app/n8n again.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, tempfile

def run(args,**kwargs):
    p=subprocess.run(args,capture_output=True,**kwargs)
    if p.returncode:raise RuntimeError('COMMAND_FAILED: '+args[0])
    return p.stdout

class Stack:
    def __init__(self,config):
        self.c=config
        assert config['project'].startswith('printer-p9-') or config.get('operator_confirmed_project')==config['project']
        self.base=['docker','compose','-p',config['project'],'--env-file',config['env_file']]
        for file in config['compose_files']:self.base+=['-f',file]
        self.base+=['--profile','automation']
    def compose(self,*args,**kw):return run(self.base+list(args),**kw)
    def container(self,service):
        cid=self.compose('ps','-a','-q',service).decode().strip();assert cid and '\n' not in cid
        info=json.loads(run(['docker','inspect',cid]))[0]
        assert info['Config']['Labels']['com.docker.compose.project']==self.c['project']
        assert info['Config']['Labels']['com.docker.compose.service']==service
        return cid
    def sql(self,sql):
        return run(['docker','exec','-i',self.container('mysql'),'sh','-ec','MYSQL_PWD="$(cat /run/secrets/mysql_password)" exec mysql -u printer -D printer -N -B'],input=sql.encode())
    def counts(self):
        names=self.sql('SHOW TABLES').decode().splitlines()
        return {name:int(self.sql('SELECT COUNT(*) FROM `'+name+'`')) for name in names}

def hashes(folder):return {p.relative_to(folder).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob('*')) if p.is_file()}

def backup(config,dest):
    s=Stack(config);dest=Path(dest).resolve();assert not dest.exists(),'snapshot destination must be new'
    dest.mkdir(parents=True)
    # Stop all writers before dumping or copying any data; never copy a live Qdrant/SQLite volume.
    s.compose('stop','app','n8n','qdrant')
    counts=s.counts()
    dump=run(['docker','exec',s.container('mysql'),'sh','-ec','MYSQL_PWD="$(cat /run/secrets/mysql_password)" exec mysqldump -u printer --single-transaction --no-tablespaces --hex-blob --routines=false --triggers printer'])
    (dest/'database.sql').write_bytes(dump)
    for service,path,name in [('app','/data/.','private'),('qdrant','/qdrant/storage/.','qdrant'),('n8n','/home/node/.n8n/.','n8n')]:
        q=dest/name;q.mkdir();run(['docker','cp',s.container(service)+':'+path,str(q)])
    q=dest/'configuration';q.mkdir()
    shutil.copytree(config['secrets_dir'],q/'secrets')
    shutil.copy2(config['env_file'],q/'settings.env')
    for i,p in enumerate(config['compose_files']):shutil.copy2(p,q/('compose-'+str(i)+'.yaml'))
    for p in config.get('support_files',[]):shutil.copy2(p,q/Path(p).name)
    manifest=dict(version=1,counts=counts,hashes=hashes(dest),writers='STOPPED',limitations=['No live writer consistency claim','Redis cache not backed up','RabbitMQ derived wakeups reconstructed from MySQL outbox','Restore invalidates identities before ingress'])
    (dest/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    restic=config['restic'];env=dict(os.environ,RESTIC_REPOSITORY=config['repository'],RESTIC_PASSWORD_FILE=config['restic_password_file'])
    if not Path(config['repository']).exists():run([restic,'init'],env=env)
    run([restic,'backup','--tag',config['project'],str(dest)],env=env)
    run([restic,'check','--read-data'],env=env)
    snapshots=json.loads(run([restic,'snapshots','--json','--tag',config['project']],env=env))
    return dict(snapshot_id=snapshots[-1]['id'],counts=counts,files=len(manifest['hashes']),encrypted='restic',verification='check --read-data')

def materialize(config,snapshot_id,target):
    target=Path(target).resolve();assert not target.exists(),'restore destination must be new'
    env=dict(os.environ,RESTIC_REPOSITORY=config['repository'],RESTIC_PASSWORD_FILE=config['restic_password_file'])
    run([config['restic'],'restore',snapshot_id,'--target',str(target)],env=env)
    manifests=list(target.rglob('manifest.json'));assert len(manifests)==1
    folder=manifests[0].parent;m=json.loads(manifests[0].read_text())
    actual=hashes(folder);actual.pop('manifest.json',None);assert actual==m['hashes'],'snapshot hash mismatch'
    return folder

def restore(config,folder):
    """Only provisioned fresh MySQL and created-but-never-started destination containers."""
    s=Stack(config);folder=Path(folder).resolve();m=json.loads((folder/'manifest.json').read_text())
    actual=hashes(folder);actual.pop('manifest.json',None);assert actual==m['hashes'],'snapshot hash mismatch'
    assert s.sql('SHOW TABLES').strip()==b'','refuse nonempty target database'
    for service,path,name in [('app','/data','private'),('qdrant','/qdrant/storage','qdrant'),('n8n','/home/node/.n8n','n8n')]:
        info=json.loads(run(['docker','inspect',s.container(service)]))[0];assert info['State']['Status']=='created','restore target must never have run'
        run(['docker','cp',str(folder/name)+ '/.',s.container(service)+':'+path])
    s.sql((folder/'database.sql').read_text(encoding='utf-8'))
    assert s.counts()==m['counts'],'restored table counts mismatch'
    s.compose('run','--rm','--no-deps','--user','0','--entrypoint','sh','app','-ec','chown -R 10001:10001 /data')
    s.compose('run','--rm','--no-deps','--user','0','--entrypoint','sh','n8n','-ec','chown -R 1000:1000 /home/node/.n8n')
    # app/proxy/n8n stay stopped; invalidate links/credential versions offline before ingress.
    s.compose('run','--rm','--no-deps','-e','KNOWLEDGE_JOBS_ENABLED=false','-e','AI_ENABLED=false','-e','IDENTITY_MAINTENANCE_ENABLED=true','-e','IDENTITY_VERIFIED=true',
              '-e','IDENTITY_OPERATOR_REFERENCE=isolated-restore','-e','IDENTITY_VERIFICATION_REFERENCE=backup-manifest',
              'app','--identity-maintenance=invalidate-restored')
    return dict(tables=len(m['counts']),row_counts_match=True,identity_invalidation='offline command before ingress',writers='STOPPED')

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['backup','materialize','restore']);p.add_argument('--config',required=True);p.add_argument('--path',required=True);p.add_argument('--snapshot');a=p.parse_args()
    c=json.loads(Path(a.config).read_text())
    if a.action=='backup':result=backup(c,a.path)
    elif a.action=='materialize':result={'path':str(materialize(c,a.snapshot,a.path))}
    else:result=restore(c,a.path)
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
