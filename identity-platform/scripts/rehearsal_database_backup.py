"""Back up the isolated rehearsal and restore into new databases only; no in-place rollback."""
import argparse
import hashlib
import json
import re
import subprocess
import uuid
from pathlib import Path

HOME=Path(__file__).resolve().parents[1]
LOCAL=HOME/'.local/rehearsal'
PG='identity-platform-postgres-1'
MYSQL='identity-migration-rehearsal-mysql-1'

def run(command,**options):
    result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**options)
    if result.returncode:raise RuntimeError('Database command failed; exit '+str(result.returncode))
    return result.stdout

def checksum(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def restore_test_service_permissions(target):
    state=json.loads((LOCAL/'environment.json').read_text(encoding='utf-8'))
    schema,role=state['platformSchema'],state['platformRole']
    if not re.fullmatch('identity_recovery_[a-f0-9]{12}',target) or not re.fullmatch('identity_r_app_[a-f0-9]+',schema) or not re.fullmatch('identity_r_role_[a-f0-9]+',role):
        raise ValueError('Only the isolated recovery database and service role are allowed')
    sql=f'GRANT USAGE,CREATE ON SCHEMA {schema} TO {role}; GRANT ALL ON ALL TABLES IN SCHEMA {schema} TO {role}; GRANT ALL ON ALL SEQUENCES IN SCHEMA {schema} TO {role};'
    run(['docker','exec','-i',PG,'psql','-U','identity','-d',target,'-v','ON_ERROR_STOP=1'],input=sql.encode())
    sql=f"GRANT ALL PRIVILEGES ON `{target}`.* TO 'identity_test'@'%';"
    run(['docker','exec','-i',MYSQL,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],input=sql.encode())

def backup():
    state=json.loads((LOCAL/'environment.json').read_text(encoding='utf-8'))
    schemas=[state['keycloakSchema'],state['platformSchema']]
    if not all(re.fullmatch('identity_r_(kc|app)_[a-f0-9]+',schema) for schema in schemas):raise ValueError('Only isolated rehearsal schemas may be backed up by this helper')
    directory=LOCAL/('backup-'+uuid.uuid4().hex[:12]);directory.mkdir()
    pg=directory/'identity.dump';mysql=directory/'tapcanvas.sql'
    pg.write_bytes(run(['docker','exec',PG,'pg_dump','-U','identity','-d','identity','-Fc','--no-owner','--no-acl',*sum((['-n',schema] for schema in schemas),[])]))
    mysql.write_bytes(run(['docker','exec',MYSQL,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --routines --triggers identity_rehearsal']))
    manifest={'schemas':schemas,'postgresContainer':PG,'mysqlContainer':MYSQL,'files':{path.name:checksum(path) for path in (pg,mysql)}}
    (directory/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({'backup':str(directory),'files':list(manifest['files'])}))

def restore(directory):
    directory=Path(directory).resolve()
    if not directory.is_relative_to(LOCAL.resolve()) or not directory.name.startswith('backup-'):raise ValueError('Backup must be inside the isolated rehearsal directory')
    manifest=json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
    if len(manifest['schemas'])!=2 or not all(re.fullmatch('identity_r_(kc|app)_[a-f0-9]+',schema) for schema in manifest['schemas']):raise ValueError('Invalid rehearsal schema identifiers')
    if set(manifest['files'])!={'identity.dump','tapcanvas.sql'}:raise ValueError('Backup file manifest mismatch')
    for name,expected in manifest['files'].items():
        if checksum(directory/name)!=expected:raise ValueError('Backup checksum mismatch')
    target='identity_recovery_'+uuid.uuid4().hex[:12]
    # Creation fails if a database already exists. Never overwrite current databases.
    run(['docker','exec',PG,'createdb','-U','identity',target])
    run(['docker','exec','-i',PG,'pg_restore','-U','identity','-d',target,'--no-owner','--no-acl','--exit-on-error'],input=(directory/'identity.dump').read_bytes())
    run(['docker','exec',MYSQL,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE '+target+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"'])
    run(['docker','exec','-i',MYSQL,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot '+target],input=(directory/'tapcanvas.sql').read_bytes())
    # Compare the complete source/restored row counts for the identity and ledger tables.
    counts={}
    for schema,tables in [(manifest['schemas'][1],['platform_user','platform_app_account','platform_wallet_operation','platform_wallet_ledger'])]:
        for table in tables:
            sql=f'SELECT count(*) FROM {schema}.{table}'
            source=run(['docker','exec',PG,'psql','-U','identity','-d','identity','-Atc',sql]).strip()
            restored=run(['docker','exec',PG,'psql','-U','identity','-d',target,'-Atc',sql]).strip()
            if source!=restored:raise ValueError('Row count changed during recovery rehearsal: '+table)
            counts[table]=int(restored)
    for table in ['users','projects','flows','assets','task_results','platform_credit_outbox']:
        sql=f'SELECT (SELECT count(*) FROM identity_rehearsal.{table})=(SELECT count(*) FROM {target}.{table})'
        verified=run(['docker','exec',MYSQL,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "'+sql+'"']).strip()
        if verified!=b'1':raise ValueError('MySQL row count mismatch: '+table)
    restore_test_service_permissions(target)
    result={'restoredPostgresDatabase':target,'restoredMysqlDatabase':target,'verifiedPlatformRows':counts,'verifiedProductTables':6,'originalDatabasesUntouched':True,'testServicePermissionsRestored':True}
    (directory/'recovery-report.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['backup','restore']);parser.add_argument('--backup');args=parser.parse_args()
    if args.command=='backup':backup()
    else:restore(args.backup)
