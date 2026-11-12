"""Create isolated local rehearsal configuration; no production identities are copied."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import uuid

HOME=Path(__file__).resolve().parents[1]
directory=HOME/'.local'/'rehearsal'
directory.mkdir(parents=True,exist_ok=True)
state_path=directory/'environment.json'
if state_path.exists():
    state=json.loads(state_path.read_text(encoding='utf-8'))
else:
    suffix=uuid.uuid4().hex[:12]
    state={'keycloakSchema':'identity_r_kc_'+suffix,'platformSchema':'identity_r_app_'+suffix,'platformRole':'identity_r_role_'+suffix,
        'keycloak':'http://127.0.0.1:8280','portal':'http://127.0.0.1:8281','productB':'http://127.0.0.1:8282'}
    state_path.write_text(json.dumps(state,indent=2),encoding='utf-8')
original=json.loads((HOME/'.local/settings.json').read_text(encoding='utf-8'))
settings_path=directory/'settings.json'
if not settings_path.exists():
    data={key:secrets.token_urlsafe(32) for key in ('admin_password','consumer_web','consumer_api','workforce_web','workforce_api')}
    data.update({'db_password':original['db_password'],'admin_username':'rehearsal-admin'})
    settings_path.write_text(json.dumps(data,indent=2),encoding='utf-8')
for key,value in {'IDENTITY_LOCAL_DIR':str(directory),'IDENTITY_KEYCLOAK_URL':state['keycloak'],
    'IDENTITY_PORTAL_ORIGIN':state['portal'],'IDENTITY_PRODUCT_B_ORIGIN':state['productB'],
    'IDENTITY_KEYCLOAK_SCHEMA':state['keycloakSchema'],'IDENTITY_BUSINESS_SCHEMA':state['platformSchema'],'IDENTITY_BUSINESS_ROLE':state['platformRole']}.items():
    os.environ[key]=value
sql='CREATE SCHEMA IF NOT EXISTS '+state['keycloakSchema']+';'
result=subprocess.run(['docker','compose','--env-file','.local/compose.env','exec','-T','postgres','psql','-v','ON_ERROR_STOP=1','-U','identity','-d','identity'],cwd=HOME,input=sql,text=True,capture_output=True)
if result.returncode:
    raise RuntimeError('Could not create isolated Keycloak schema')
from identity_admin import prepare
prepare()
env={key:value for key,value in os.environ.items() if key.startswith('IDENTITY_')}
(directory/'process-env.json').write_text(json.dumps(env,indent=2),encoding='utf-8')
print(json.dumps({'keycloak':state['keycloak'],'portal':state['portal'],'isolatedSchemas':[state['keycloakSchema'],state['platformSchema']]}))
