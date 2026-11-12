"""Provision scoped clients and write ignored local config. Never imports product users."""
import argparse
import base64
import json
import secrets
import urllib.parse
import urllib.error
import urllib.request
from identity_admin import Admin, BASE, LOCAL, HTTP, settings, prepare


def origin(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('An origin without credentials or path is required')
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1', '::1')):
        raise ValueError('HTTPS or loopback required')
    return value.rstrip('/')


def registration(admin):
    alias = 'identity-consumer-registration'
    flows = admin.call('GET', '/consumer/authentication/flows')
    if not any(row['alias'] == alias for row in flows):
        admin.call('POST', '/consumer/authentication/flows/registration/copy', {'newName': alias})
    path = '/consumer/authentication/flows/' + alias + '/executions'
    executions = admin.call('GET', path)
    if not any(row.get('providerId') == 'identity-consumer-registration' for row in executions):
        original = next(row for row in executions if row.get('providerId') == 'registration-user-creation')
        original['requirement'] = 'DISABLED'
        admin.call('PUT', path, original)
        form = next(row for row in executions if row.get('authenticationFlow') and row.get('providerId') == 'registration-page-form')
        form_alias = admin.call('GET', '/consumer/authentication/flows/' + form['flowId'])['alias']
        admin.call('POST', '/consumer/authentication/flows/' + urllib.parse.quote(form_alias, safe='') + '/executions/execution', {'provider': 'identity-consumer-registration'})
        current = next(row for row in admin.call('GET', path) if row.get('providerId') == 'identity-consumer-registration')
        current['requirement'] = 'REQUIRED'
        admin.call('PUT', path, current)
        for _ in executions:
            admin.call('POST', '/consumer/authentication/executions/' + current['id'] + '/raise-priority')
    # Staff imports may omit email. Only public self-registration enforces email.
    profile = admin.call('GET', '/consumer/users/profile')
    if not any(attr['name']=='migration_digest' for attr in profile['attributes']):
        profile['attributes'].append({'name':'migration_digest','permissions':{'view':['admin'],'edit':['admin']}})
    for attr in profile['attributes']:
        if attr['name'] == 'email':
            attr.pop('required', None)
    admin.call('PUT', '/consumer/users/profile', profile)
    admin.call('PUT', '/consumer', {'registrationFlow': alias, 'registrationEmailAsUsername': False, 'loginWithEmailAllowed': True})


def provision(admin, web, api, portal):
    data = settings()
    scopes = {s['name']: s['id'] for s in admin.call('GET', '/consumer/client-scopes')}
    for name in ('platform.integrate', 'platform.delegate', 'platform.manage'):
        if name not in scopes:
            admin.call('POST', '/consumer/client-scopes', {'name': name, 'protocol': 'openid-connect',
                'attributes': {'include.in.token.scope': 'true', 'display.on.consent.screen': 'false'}})
    scopes = {s['name']: s['id'] for s in admin.call('GET', '/consumer/client-scopes')}
    clients = [
        ('tapcanvas-web', 'tapcanvas_web', False, ['basic', 'profile', 'email', 'roles'], 'tapcanvas-api'),
        ('tapcanvas-service', 'tapcanvas_service', True, ['basic', 'platform.integrate', 'platform.delegate'], 'identity-platform-internal'),
        ('identity-platform-operations', 'platform_operations', True, ['basic', 'platform.manage'], 'identity-platform-internal'),
    ]
    for name, key, service, attachments, audience in clients:
        old = admin.client('consumer', name)
        attrs = {'identity.platform.client': 'true'}
        if name == 'identity-platform-operations':
            attrs['identity.platform.guard'] = 'true'
        if not service:
            attrs.update({'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': web + '/',
                'backchannel.logout.url': api + '/auth/oidc/backchannel-logout', 'backchannel.logout.session.required': 'true'})
        payload = {'clientId': name, 'enabled': True, 'protocol': 'openid-connect', 'publicClient': False,
            'clientAuthenticatorType': 'client-secret', 'standardFlowEnabled': not service,
            'directAccessGrantsEnabled': False, 'implicitFlowEnabled': False, 'serviceAccountsEnabled': service,
            'fullScopeAllowed': False, 'redirectUris': [] if service else [api + '/auth/oidc/callback'],
            'webOrigins': [], 'attributes': attrs, 'protocolMappers': [
                {'name': 'platform-audience', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-audience-mapper',
                 'config': {'included.custom.audience': audience, 'access.token.claim': 'true', 'id.token.claim': 'false'}}]}
        if not old:
            payload['secret'] = secrets.token_urlsafe(32)
        admin.call('PUT' if old else 'POST', '/consumer/clients' + ('/' + old['id'] if old else ''), payload)
        current = admin.client('consumer', name)
        path = '/consumer/clients/' + current['id']
        for kind in ('default', 'optional'):
            for scope in admin.call('GET', path + '/' + kind + '-client-scopes'):
                if kind == 'optional' or scope['name'] not in attachments:
                    admin.call('DELETE', path + '/' + kind + '-client-scopes/' + scope['id'])
        for scope in attachments:
            admin.call('PUT', path + '/default-client-scopes/' + scopes[scope])
        data[key] = admin.call('GET', path + '/client-secret')['value']
    for name in ('consumer-web', 'product-b-web'):
        current = admin.client('consumer', name)
        if current:
            attrs = current.get('attributes', {})
            attrs['identity.platform.client'] = 'true'
            admin.call('PUT', '/consumer/clients/' + current['id'], {'attributes': attrs})
    registration(admin)
    data.setdefault('tapcanvas_session_key', base64.b64encode(secrets.token_bytes(32)).decode())
    (LOCAL / 'settings.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
    env_path = LOCAL / 'tapcanvas.identity.env'
    previous = dict(line.split('=', 1) for line in env_path.read_text(encoding='utf-8').splitlines() if '=' in line) if env_path.exists() else {}
    credit_authority = previous.get('IDENTITY_CREDIT_AUTHORITY', 'local')
    if credit_authority not in ('local', 'platform'):
        raise ValueError('Existing credit authority must be local or platform')
    env = {'AUTH_LOGIN_MODE': 'oidc', 'IDENTITY_ISSUER': BASE + '/realms/consumer',
        'IDENTITY_CLIENT_ID': 'tapcanvas-web', 'IDENTITY_CLIENT_SECRET': data['tapcanvas_web'],
        'IDENTITY_SERVICE_CLIENT_ID': 'tapcanvas-service', 'IDENTITY_SERVICE_CLIENT_SECRET': data['tapcanvas_service'],
        'IDENTITY_PLATFORM_URL': portal, 'IDENTITY_CALLBACK_URL': api + '/auth/oidc/callback',
        'IDENTITY_WEB_ORIGIN': web, 'IDENTITY_SESSION_ENCRYPTION_KEY': data['tapcanvas_session_key'],
        'IDENTITY_CREDIT_AUTHORITY': credit_authority}
    env_path.write_text('\n'.join(k + '=' + v for k, v in env.items()) + '\n', encoding='utf-8')
    (LOCAL / 'platform-operations.properties').write_text('identity.operations-client-secret=' + data['platform_operations'] + '\n', encoding='ascii')
    prepare()
    print('Scoped clients ready. Private configuration written under the ignored local directory; credit authority: ' + credit_authority)


def register_application(web, portal):
    data = settings()
    form = urllib.parse.urlencode({'grant_type': 'client_credentials', 'client_id': 'identity-platform-operations',
        'client_secret': data['platform_operations']}).encode()
    with HTTP.open(urllib.request.Request(BASE + '/realms/consumer/protocol/openid-connect/token', data=form), timeout=10) as response:
        token = json.load(response)['access_token']
    lookup = urllib.request.Request(portal + '/internal/v1/admin/applications/tapcanvas',
        headers={'Authorization': 'Bearer ' + token})
    try:
        with HTTP.open(lookup, timeout=10) as response:
            current = json.load(response)
        if current['launchUrl'] != web or current['clientId'] != 'tapcanvas-web' or current['serviceClientId'] != 'tapcanvas-service':
            raise ValueError('Existing application differs; review and update its admin configuration explicitly')
        print('TapCanvas already registered; existing access and migration settings preserved.')
        return
    except urllib.error.HTTPError as failure:
        if failure.code != 404:
            raise
    payload = {'id': 'tapcanvas', 'name': 'TapCanvas', 'launchUrl': web, 'clientId': 'tapcanvas-web',
        'serviceClientId': 'tapcanvas-service', 'audience': 'tapcanvas-api', 'enabled': True,
        'consumerAllowed': True, 'migrationReady': False, 'delegatedBilling': True}
    request = urllib.request.Request(portal + '/internal/v1/admin/applications', data=json.dumps(payload).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with HTTP.open(request, timeout=10) as response:
        response.read()
    print('TapCanvas registered with new-account provisioning CLOSED until migration review.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--web-origin', type=origin, default='http://127.0.0.1:5173')
    parser.add_argument('--api-origin', type=origin, default='http://127.0.0.1:8788')
    parser.add_argument('--portal-origin', type=origin, default='http://127.0.0.1:8081')
    parser.add_argument('--register-application', action='store_true')
    args = parser.parse_args()
    provision(Admin(), args.web_origin, args.api_origin, args.portal_origin)
    if args.register_application:
        register_application(args.web_origin, args.portal_origin)
