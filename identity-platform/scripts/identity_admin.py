"""Local environment initialization and controlled employee lifecycle; standard library only."""
import argparse
import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HOME = Path(__file__).resolve().parents[1]
LOCAL = Path(os.environ.get('IDENTITY_LOCAL_DIR', str(HOME / '.local'))).resolve()
BASE = os.environ.get('IDENTITY_KEYCLOAK_URL', 'http://127.0.0.1:8180').rstrip('/')
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PORTAL = os.environ.get('IDENTITY_PORTAL_ORIGIN', 'http://127.0.0.1:8081').rstrip('/')
PRODUCT_B = os.environ.get('IDENTITY_PRODUCT_B_ORIGIN', 'http://127.0.0.1:8082').rstrip('/')
BUSINESS_SCHEMA = os.environ.get('IDENTITY_BUSINESS_SCHEMA', 'identity_business')
BUSINESS_ROLE = os.environ.get('IDENTITY_BUSINESS_ROLE', 'identity_app')
if not all(re.fullmatch('[a-z_][a-z0-9_]{0,60}', v) for v in (BUSINESS_SCHEMA,BUSINESS_ROLE)):
    raise ValueError('Invalid local database identifier')


def settings():
    return json.loads((LOCAL / 'settings.json').read_text(encoding='utf-8'))


def prepare():
    LOCAL.mkdir(exist_ok=True)
    path = LOCAL / 'settings.json'
    if not path.exists():
        data = {k: secrets.token_urlsafe(32) for k in ['db_password', 'admin_password', 'consumer_web', 'consumer_api', 'workforce_web', 'workforce_api']}
        data['admin_username'] = 'identity-admin'
        path.write_text(json.dumps(data, indent=2), encoding='utf-8')
    data = settings()
    for key in ['platform_broker', 'product_b_web', 'product_b_api', 'business_db']:
        data.setdefault(key, secrets.token_urlsafe(32))
    path.write_text(json.dumps(data, indent=2), encoding='utf-8')
    (LOCAL / 'compose.env').write_text('IDENTITY_DB_PASSWORD=' + data['db_password'] + '\n', encoding='utf-8')
    props = {'kc.db': 'postgres', 'kc.db-url': 'jdbc:postgresql://127.0.0.1:55432/identity',
             'kc.db-username': 'identity', 'kc.db-password': data['db_password'],
             'kc.bootstrap-admin-username': data['admin_username'], 'kc.bootstrap-admin-password': data['admin_password'],
             'identity.wecom.mock': 'true'}
    if os.environ.get('IDENTITY_KEYCLOAK_SCHEMA'):
        props['kc.db-schema'] = os.environ['IDENTITY_KEYCLOAK_SCHEMA']
    (LOCAL / 'keycloak.properties').write_text('\n'.join(f'{k}={v}' for k, v in props.items()) + '\n', encoding='utf-8')
    shared = (f'spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/identity?currentSchema={BUSINESS_SCHEMA}\n'
              f'spring.datasource.username={BUSINESS_ROLE}\nspring.datasource.password={data["business_db"]}\n'
              'server.servlet.session.cookie.secure=false\n')
    if data.get('platform_operations'):
        shared+='identity.operations-client-secret='+data['platform_operations']+'\n'
    (LOCAL / 'demo-product.properties').write_text(shared + f'identity.consumer-client-secret={data["consumer_web"]}\n', encoding='utf-8')
    (LOCAL / 'demo-product-b.properties').write_text(shared +
        f'identity.consumer-client-secret={data["product_b_web"]}\n'
        'server.port=8082\nserver.servlet.session.cookie.name=PRODUCT_B_SESSION\n'
        'identity.product-id=product-b\nidentity.product-name=灵感空间 B\nidentity.api-audience=product-b-api\n'
        'identity.client-id=product-b-web\nidentity.other-product=http://127.0.0.1:8081\n', encoding='utf-8')
    # Java .properties files use ISO-8859-1; keep all generated non-ASCII values escaped.
    for properties in LOCAL.glob('demo-product*.properties'):
        text = properties.read_text(encoding='utf-8')
        properties.write_text(text.encode('ascii', 'backslashreplace').decode('ascii'), encoding='ascii')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=HOME, text=True).strip()
    (LOCAL / 'source-version.txt').write_text(commit + '\n999.0.0-SNAPSHOT\n', encoding='utf-8')
    print('Local settings ready; credentials are in ignored .local/settings.json')


class AdminError(RuntimeError):
    """Sanitized endpoint/status context, without response bodies or credentials."""


class Admin:
    def __init__(self):
        self.refresh()

    def refresh(self):
        data = settings()
        body = urllib.parse.urlencode({'client_id': 'admin-cli', 'grant_type': 'password',
                                     'username': data['admin_username'], 'password': data['admin_password']}).encode()
        req = urllib.request.Request(BASE + '/realms/master/protocol/openid-connect/token', data=body)
        with HTTP.open(req, timeout=10) as response:
            self.token = json.load(response)['access_token']
        self.expires=time.monotonic()+45

    def call(self, method, path, data=None, missing=False):
        if time.monotonic()>self.expires:self.refresh()
        body = None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(BASE + '/admin/realms' + path, data=body, method=method,
                                     headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        try:
            with HTTP.open(req, timeout=20) as response:
                text = response.read()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            # Error body may contain private configuration; never print it.
            raise AdminError(f'Keycloak admin operation failed: {method} {urllib.parse.urlsplit(path).path}, HTTP {error.code}') from None

    def client(self, realm, name):
        rows = self.call('GET', f'/{realm}/clients?clientId={urllib.parse.quote(name)}')
        return rows[0] if rows else None


def flow(admin, realm, alias, executions):
    existing = next((f for f in admin.call('GET', f'/{realm}/authentication/flows') if f['alias'] == alias), None)
    if existing:
        return
    admin.call('POST', f'/{realm}/authentication/flows', {'alias': alias, 'providerId': 'basic-flow', 'topLevel': True, 'builtIn': False})
    for provider, requirement in executions:
        admin.call('POST', f'/{realm}/authentication/flows/{alias}/executions/execution', {'provider': provider})
        current = admin.call('GET', f'/{realm}/authentication/flows/{alias}/executions')
        item = next(e for e in current if e.get('providerId') == provider)
        item['requirement'] = requirement
        admin.call('PUT', f'/{realm}/authentication/flows/{alias}/executions', item)


def initialize():
    prepare()
    admin, data = Admin(), settings()
    baseline = json.loads((HOME / 'realm-config/baseline.json').read_text(encoding='utf-8'))
    for realm in ['consumer', 'workforce']:
        existing = admin.call('GET', '/' + realm, missing=True)
        if existing and existing.get('attributes', {}).get('identity.managed') != 'true':
            raise RuntimeError(f'Refusing to modify unmanaged realm {realm}')
        payload = {**baseline, 'realm': realm, 'enabled': True,
                   'displayName': '产品账号' if realm == 'consumer' else '公司员工登录',
                   'registrationAllowed': realm == 'consumer', 'registrationEmailAsUsername': realm == 'consumer',
                   'loginWithEmailAllowed': realm == 'consumer', 'duplicateEmailsAllowed': False,
                   'verifyEmail': realm == 'consumer', 'resetPasswordAllowed': realm == 'consumer',
                   'editUsernameAllowed': False, 'rememberMe': False,
                   'ssoSessionIdleTimeout': 86400 if realm == 'consumer' else 1800,
                   'ssoSessionMaxLifespan': 604800 if realm == 'consumer' else 28800,
                   'attributes': {'identity.managed': 'true', 'identity.force-password-logout': 'true'},
                   'smtpServer': {'host': '127.0.0.1', 'port': '1025', 'from': 'accounts@identity.test', 'fromDisplayName': 'Identity', 'ssl': 'false', 'starttls': 'false', 'auth': 'false'}}
        admin.call('PUT' if existing else 'POST', '/' + realm if existing else '', payload)
        # Both Realms use a minimal explicit profile; employee identity cannot be edited by users.
        attrs = []
        for name in ['username', 'email', 'firstName', 'lastName', 'employee_ref', 'migration_digest']:
            attr = {'name': name, 'displayName': '${' + name + '}',
                    'permissions': {'view': ['admin', 'user'], 'edit': ['admin', 'user'] if realm == 'consumer' and name not in ('employee_ref','migration_digest') else ['admin']}}
            if name == 'username':
                attr['required'] = {'roles': ['admin', 'user']}
            if name == 'email': attr['validations'] = {'email': {}, 'length': {'max': 254}}
            if name == 'username': attr['validations'] = {'length': {'min': 1, 'max': 255}, 'username-prohibited-characters': {}}
            attrs.append(attr)
        admin.call('PUT', f'/{realm}/users/profile', {'attributes': attrs, 'unmanagedAttributePolicy': None})
        for kind in ['web', 'api']:
            name = realm + '-' + kind
            client = admin.client(realm, name)
            value = {'clientId': name, 'enabled': True, 'protocol': 'openid-connect', 'publicClient': False,
                     'clientAuthenticatorType': 'client-secret', 'standardFlowEnabled': kind == 'web',
                     'directAccessGrantsEnabled': False, 'implicitFlowEnabled': False, 'serviceAccountsEnabled': False,
                     'fullScopeAllowed': False, 'defaultClientScopes': ['basic', 'profile', 'email', 'roles'],
                     'optionalClientScopes': [], 'attributes': {}, 'redirectUris': [], 'webOrigins': []}
            if not client: value['secret'] = data[realm + '_' + kind]
            if kind == 'web':
                origin = PORTAL
                value['redirectUris'] = [origin + '/login/oauth2/code/' + realm]
                value['attributes'] = {'identity.platform.client': 'true', 'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': origin + '/',
                    'backchannel.logout.url': origin + '/logout/connect/back-channel/' + realm, 'backchannel.logout.session.required': 'true'}
                value['protocolMappers'] = [{'name': 'api-audience', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-audience-mapper',
                    'config': {'included.client.audience': realm + '-api', 'access.token.claim': 'true', 'id.token.claim': 'false', 'introspection.token.claim': 'true'}}]
            admin.call('PUT' if client else 'POST', f'/{realm}/clients' + ('/' + client['id'] if client else ''), value)
            # Preserve remotely rotated client secrets, and refresh the local ignored configuration.
            current = admin.client(realm, name)
            # Updating ClientRepresentation does not reconcile scope attachments on existing clients.
            scopes = {s['name']: s['id'] for s in admin.call('GET', f'/{realm}/client-scopes')}
            wanted = {'basic', 'profile', 'email', 'roles'}
            for scope in admin.call('GET', f'/{realm}/clients/{current["id"]}/default-client-scopes'):
                if scope['name'] not in wanted:
                    admin.call('DELETE', f'/{realm}/clients/{current["id"]}/default-client-scopes/{scope["id"]}')
            for scope in wanted:
                admin.call('PUT', f'/{realm}/clients/{current["id"]}/default-client-scopes/{scopes[scope]}')
            for scope in admin.call('GET', f'/{realm}/clients/{current["id"]}/optional-client-scopes'):
                admin.call('DELETE', f'/{realm}/clients/{current["id"]}/optional-client-scopes/{scope["id"]}')
            data[realm + '_' + kind] = admin.call('GET', f'/{realm}/clients/{current["id"]}/client-secret')['value']
        if realm == 'consumer':
            actions = admin.call('GET', f'/{realm}/authentication/required-actions')
            for action in actions:
                if action['alias'] == 'UPDATE_EMAIL':
                    action['enabled'] = True
                    admin.call('PUT', f'/{realm}/authentication/required-actions/UPDATE_EMAIL', action)
    (LOCAL / 'settings.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    prepare()
    realm = 'workforce'
    api, web = admin.client(realm, 'workforce-api'), admin.client(realm, 'workforce-web')
    role = admin.call('GET', f'/{realm}/clients/{api["id"]}/roles/employee', missing=True)
    if not role: admin.call('POST', f'/{realm}/clients/{api["id"]}/roles', {'name': 'employee'})
    role = admin.call('GET', f'/{realm}/clients/{api["id"]}/roles/employee')
    admin.call('POST', f'/{realm}/clients/{web["id"]}/scope-mappings/clients/{api["id"]}', [role])
    flow(admin, realm, 'identity-deny-unlinked', [('deny-access-authenticator', 'REQUIRED')])
    flow(admin, realm, 'identity-workforce-mfa', [('identity-workforce-guard', 'REQUIRED'), ('auth-otp-form', 'REQUIRED')])
    alias = 'identity-workforce-browser'
    if not any(f['alias'] == alias for f in admin.call('GET', f'/{realm}/authentication/flows')):
        admin.call('POST', f'/{realm}/authentication/flows', {'alias': alias, 'providerId': 'basic-flow', 'topLevel': True, 'builtIn': False})
        admin.call('POST', f'/{realm}/authentication/flows/{alias}/executions/flow', {'alias': 'identity-employee-proof', 'type': 'basic-flow', 'provider': 'basic-flow'})
        for provider in ['auth-cookie', 'identity-provider-redirector']:
            admin.call('POST', f'/{realm}/authentication/flows/identity-employee-proof/executions/execution', {'provider': provider})
        for item in admin.call('GET', f'/{realm}/authentication/flows/identity-employee-proof/executions'):
            item['requirement'] = 'ALTERNATIVE'
            admin.call('PUT', f'/{realm}/authentication/flows/identity-employee-proof/executions', item)
            if item.get('providerId') == 'identity-provider-redirector':
                admin.call('POST', f'/{realm}/authentication/executions/{item["id"]}/config', {'alias': 'identity-wecom-default', 'config': {'defaultProvider': 'wecom-corp'}})
        admin.call('POST', f'/{realm}/authentication/flows/{alias}/executions/execution', {'provider': 'identity-workforce-guard'})
        for item in admin.call('GET', f'/{realm}/authentication/flows/{alias}/executions'):
            if item.get('level') == 0:
                item['requirement'] = 'REQUIRED'
                admin.call('PUT', f'/{realm}/authentication/flows/{alias}/executions', item)
    admin.call('PUT', f'/{realm}', {'browserFlow': alias})
    idp = admin.call('GET', f'/{realm}/identity-provider/instances/wecom-corp', missing=True)
    if not idp:
        admin.call('POST', f'/{realm}/identity-provider/instances', {'alias': 'wecom-corp', 'providerId': 'wecom-corp', 'displayName': '企业微信登录',
            'enabled': True, 'storeToken': False, 'trustEmail': False, 'linkOnly': False,
            'firstBrokerLoginFlowAlias': 'identity-deny-unlinked', 'postBrokerLoginFlowAlias': 'identity-workforce-mfa',
            'config': {'corpId': 'mock-corp', 'agentId': '1000001', 'secretEnv': 'WECOM_APP_SECRET', 'syncMode': 'IMPORT', 'mockBaseUrl': 'http://127.0.0.1:9099/wecom'}})
    # Remove employee self-service account/link management; OTP enrollment remains a required action.
    account = admin.client(realm, 'account')
    default = admin.call('GET', f'/{realm}/roles/default-roles-workforce')
    composites = admin.call('GET', f'/{realm}/roles-by-id/{default["id"]}/composites')
    remove = [r for r in composites if r.get('containerId') == account['id'] and r['name'] in ['manage-account', 'manage-account-links']]
    if remove: admin.call('DELETE', f'/{realm}/roles-by-id/{default["id"]}/composites', remove)
    for ref, userid, state in [('employee-001', 'alice', 'active'), ('employee-002', 'suspended', 'suspended')]:
        if not admin.call('GET', f'/{realm}/users?username=staff-{ref}&exact=true'):
            employee(admin, ref, userid, state)
    platform(admin, data)
    print('Managed realms, clients, authentication flows and local test employees initialized.')


def platform(admin, data):
    # A dedicated business schema and role cannot access Keycloak's public schema tables.
    password = data['business_db']
    if not all(c.isalnum() or c in '-_' for c in password):
        raise ValueError('Invalid generated business database password')
    sql = (f"DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='{BUSINESS_ROLE}') THEN "
           f"CREATE ROLE {BUSINESS_ROLE} LOGIN; END IF; END $$; "
           f"ALTER ROLE {BUSINESS_ROLE} PASSWORD '{password}'; "
           f"CREATE SCHEMA IF NOT EXISTS {BUSINESS_SCHEMA} AUTHORIZATION {BUSINESS_ROLE}; "
           f"GRANT CONNECT ON DATABASE identity TO {BUSINESS_ROLE};")
    result = subprocess.run(['docker', 'compose', '--env-file', '.local/compose.env', 'exec', '-T', 'postgres',
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'identity', '-d', 'identity'], cwd=HOME,
        input=sql, text=True, capture_output=True)
    if result.returncode: raise RuntimeError('Business schema initialization failed (private SQL output suppressed)')
    def client(realm, name, secret_key, redirect, audience=None, origin=None):
        old = admin.client(realm, name)
        value = {'clientId': name, 'enabled': True, 'protocol': 'openid-connect', 'publicClient': False,
                 'clientAuthenticatorType': 'client-secret', 'standardFlowEnabled': bool(redirect),
                 'directAccessGrantsEnabled': False, 'implicitFlowEnabled': False, 'serviceAccountsEnabled': False,
                 'fullScopeAllowed': False, 'redirectUris': [redirect] if redirect else [], 'webOrigins': [],
                 'attributes': {'identity.platform.client':'true','pkce.code.challenge.method': 'S256'} if redirect else {}}
        if not old: value['secret'] = data[secret_key]
        if origin:
            value['attributes'].update({'post.logout.redirect.uris': origin + '/',
                'backchannel.logout.url': origin + '/logout/connect/back-channel/consumer', 'backchannel.logout.session.required': 'true'})
        if audience:
            value['protocolMappers'] = [{'name':'api-audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper',
                'config':{'included.client.audience':audience,'access.token.claim':'true','id.token.claim':'false'}}]
        admin.call('PUT' if old else 'POST', f'/{realm}/clients' + ('/'+old['id'] if old else ''), value)
        current = admin.client(realm, name)
        for scope in admin.call('GET', f'/{realm}/client-scopes'):
            if scope['name'] in ['basic', 'profile', 'email', 'roles']:
                admin.call('PUT', f'/{realm}/clients/{current["id"]}/default-client-scopes/{scope["id"]}')
        data[secret_key] = admin.call('GET', f'/{realm}/clients/{current["id"]}/client-secret')['value']
        return current
    client('workforce', 'platform-broker', 'platform_broker', BASE+'/realms/consumer/broker/company/endpoint')
    client('consumer', 'product-b-api', 'product_b_api', None)
    client('consumer', 'product-b-web', 'product_b_web', PRODUCT_B+'/login/oauth2/code/consumer', 'product-b-api', PRODUCT_B)
    # Permit native AIA account linking, scoped to the account permission, not admin roles.
    account = admin.client('consumer', 'account')
    role = admin.call('GET', f'/consumer/clients/{account["id"]}/roles/manage-account-links')
    for name in ['consumer-web', 'product-b-web']:
        current = admin.client('consumer', name)
        admin.call('POST', f'/consumer/clients/{current["id"]}/scope-mappings/clients/{account["id"]}', [role])
    flow(admin, 'consumer', 'identity-deny-unlinked', [('deny-access-authenticator', 'REQUIRED')])
    upstream = BASE+'/realms/workforce/protocol/openid-connect'
    value = {'alias':'company', 'providerId':'oidc', 'displayName':'企业微信', 'enabled':True,
        'storeToken':False, 'trustEmail':False, 'firstBrokerLoginFlowAlias':'identity-deny-unlinked',
        'config':{'clientId':'platform-broker','clientSecret':data['platform_broker'], 'syncMode':'IMPORT',
            'authorizationUrl':upstream+'/auth', 'tokenUrl':upstream+'/token', 'logoutUrl':upstream+'/logout',
            'userInfoUrl':upstream+'/userinfo', 'issuer':BASE+'/realms/workforce', 'jwksUrl':upstream+'/certs',
            'useJwksUrl':'true', 'validateSignature':'true', 'defaultScope':'openid profile',
            'pkceEnabled':'true', 'pkceMethod':'S256', 'backchannelSupported':'true', 'disableUserInfo':'true'}}
    old = admin.call('GET', '/consumer/identity-provider/instances/company', missing=True)
    admin.call('PUT' if old else 'POST', '/consumer/identity-provider/instances'+('/company' if old else ''), value)
    path='/consumer/identity-provider/instances/company/mappers'
    if not any(m['identityProviderMapper']=='identity-company-session' for m in admin.call('GET',path)):
        admin.call('POST',path,{'name':'verified-company-session','identityProviderAlias':'company',
            'identityProviderMapper':'identity-company-session','config':{'syncMode':'INHERIT'}})
    # Old direct workforce web clients are retained disabled for explicit migration, never silently deleted.
    old = admin.client('workforce', 'workforce-web')
    if old: admin.call('PUT', '/workforce/clients/'+old['id'], {'enabled':False})
    (LOCAL / 'settings.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    prepare()

    # Optional email is safe only with the public registration email requirement.
    from platform_setup import registration
    registration(admin)


def employee(admin, ref, userid, state):
    if not ref or not ref.strip() or not userid or not userid.strip() or ':' in userid:
        raise ValueError('Nonempty immutable employee reference and unambiguous WeCom userid required')
    realm = 'workforce'
    idp = admin.call('GET', f'/{realm}/identity-provider/instances/wecom-corp')
    key = idp['config']['corpId'] + ':' + userid
    username = 'staff-' + ref
    users = admin.call('GET', f'/{realm}/users?username={urllib.parse.quote(username)}&exact=true')
    if not users:
        admin.call('POST', f'/{realm}/users', {'username': username, 'enabled': state == 'active',
            'attributes': {'employee_ref': [ref]}, 'requiredActions': ['CONFIGURE_TOTP']})
        users = admin.call('GET', f'/{realm}/users?username={urllib.parse.quote(username)}&exact=true')
    user = users[0]
    links = admin.call('GET', f'/{realm}/users/{user["id"]}/federated-identity')
    existing = next((link for link in links if link['identityProvider'] == 'wecom-corp'), None)
    if existing and existing['userId'] != key: raise RuntimeError('Identity mapping conflict; manual review required')
    if not existing:
        admin.call('POST', f'/{realm}/users/{user["id"]}/federated-identity/wecom-corp', {'identityProvider': 'wecom-corp', 'userId': key, 'userName': userid})
    api = admin.client(realm, 'workforce-api')
    role = admin.call('GET', f'/{realm}/clients/{api["id"]}/roles/employee')
    admin.call('PUT', f'/{realm}/users/{user["id"]}', {'enabled': state == 'active'})
    admin.call('POST' if state == 'active' else 'DELETE', f'/{realm}/users/{user["id"]}/role-mappings/clients/{api["id"]}', [role])
    if state != 'active':
        admin.call('POST', f'/{realm}/users/{user["id"]}/logout')
    print(f'Employee {ref}: {state}')


def reset_otp(admin, ref):
    if not ref or not ref.strip():
        raise ValueError('An employee reference is required')
    users = admin.call('GET', '/workforce/users?username=' + urllib.parse.quote('staff-' + ref) + '&exact=true')
    if len(users) != 1:
        raise RuntimeError('Employee does not exist')
    user = users[0]
    path = '/workforce/users/' + user['id']
    # A privileged recovery operation: revoke sessions before replacing MFA enrollment.
    admin.call('POST', path + '/logout')
    for credential in admin.call('GET', path + '/credentials'):
        if credential['type'] == 'otp':
            admin.call('DELETE', path + '/credentials/' + credential['id'])
    actions = list(dict.fromkeys(user.get('requiredActions', []) + ['CONFIGURE_TOTP']))
    admin.call('PUT', path, {'requiredActions': actions})
    print(f'Employee {ref}: sessions revoked; TOTP enrollment required at next login')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['prepare', 'initialize', 'employee', 'reset-otp'])
    parser.add_argument('--ref'); parser.add_argument('--userid'); parser.add_argument('--state', choices=['active', 'suspended'], default='active')
    args = parser.parse_args()
    if args.command == 'prepare': prepare()
    elif args.command == 'initialize': initialize()
    elif args.command == 'reset-otp': reset_otp(Admin(), args.ref)
    else: employee(Admin(), args.ref, args.userid, args.state)
