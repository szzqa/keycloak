"""Generate private, loopback-only settings for the TapCanvas integration rehearsal."""
import json
from pathlib import Path
import secrets
import urllib.parse

HOME=Path(__file__).resolve().parents[1]
tap=HOME.parents[1]/'TapCanvas'
local=tap/'.tmp'/'identity-rehearsal'
local.mkdir(parents=True,exist_ok=True)
path=local/'settings.json'
if not path.exists():
    path.write_text(json.dumps({key:secrets.token_urlsafe(32) for key in ('database','root','jwt','billing')}),encoding='utf-8')
data=json.loads(path.read_text(encoding='utf-8'))
oidc=dict(line.split('=',1) for line in (HOME/'.local/rehearsal/tapcanvas.identity.env').read_text(encoding='utf-8').splitlines() if '=' in line)
env={**oidc,'IDENTITY_REHEARSAL':'true','IDENTITY_CREDIT_AUTHORITY':'platform',
    'DATABASE_URL':'mysql://identity_test:'+urllib.parse.quote(data['database'],safe='')+'@127.0.0.1:53316/identity_rehearsal',
    'REDIS_URL':'redis://127.0.0.1:56379','JWT_SECRET':data['jwt'],'BILLING_CORRELATION_SECRET':data['billing'],
    'AUTH_ALLOW_INSECURE_HTTP':'true','ACCOUNT_CREDIT_ENTRY_MODE':'request','ACCOUNT_API_MANAGEMENT_ENABLED':'false','ACCOUNT_ADMIN_ENTRY_ENABLED':'false',
    'PERSONAL_DAILY_QUOTA_RESET_ENABLED':'true','PERSONAL_DAILY_QUOTA_RESET_CRON':'0 0 * * *',
    'AGENT_CHAT_ENABLED':'false','ADMIN_MODEL_INTEGRATION_ENABLED':'false','ADMIN_HYPCUP_BINDINGS_ENABLED':'false',
    'HYPCUP_BENEFIT_NOTICE_ENABLED':'false','NODE_ENV':'test','FOMFLO_REDIS_NAMESPACE':'identity-rehearsal',
    'IDENTITY_TEST_DB_PASSWORD':data['database'],'IDENTITY_TEST_ROOT_PASSWORD':data['root']}
(local/'run.env').write_text('\n'.join(k+'='+v for k,v in env.items())+'\n',encoding='utf-8')
print('Private TapCanvas rehearsal settings prepared for MySQL 53316, Redis 56379 and API 8877.')
