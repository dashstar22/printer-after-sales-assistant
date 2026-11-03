"""Refuse an older application's migration set before replacing a stopped instance."""
from pathlib import Path
import argparse, importlib.util, json, re, zipfile
spec=importlib.util.spec_from_file_location('snapshot',Path(__file__).with_name('snapshot.py'));s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)

def check(config,jar):
    with zipfile.ZipFile(jar) as z:
        versions={int(re.search(r'/V(\d+)__',n)[1]) for n in z.namelist() if re.search(r'/db/migration/V\d+__.*\.sql$',n)}
    current={int(n) for n in s.Stack(config).sql('SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL').decode().splitlines()}
    missing=sorted(current-versions)
    return dict(compatible=not missing,database_versions=sorted(current),jar_versions=sorted(versions),missing_from_jar=missing,policy='STOP_WRITES_AND_RESTORE_COMPATIBLE_SNAPSHOT_IF_SCHEMA_IS_NEWER')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--jar',required=True);a=p.parse_args();r=check(json.loads(Path(a.config).read_text()),a.jar);print(json.dumps(r,indent=2));raise SystemExit(0 if r['compatible'] else 2)
