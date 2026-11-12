"""Compile reviewed exports and apply a checksummed account batch. Dry-run is the default."""
import argparse
import base64
import hashlib
import json
import re
import uuid
import copy
import time
import urllib.parse
import urllib.request
from pathlib import Path
from identity_admin import Admin, AdminError, BASE, LOCAL, HTTP, settings
from platform_setup import origin


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def credential(record):
    if not record:
        return None
    if record.get('algorithm') != 'pbkdf2-sha256' or record.get('iterations') != 210000:
        raise ValueError('Unsupported credential algorithm')
    if not re.fullmatch('[0-9a-f]{64}', record.get('hashHex', '')) or not re.fullmatch('[0-9a-fA-F-]{36}', record.get('saltUtf8', '')):
        raise ValueError('Unsupported credential encoding')
    return {'type': 'password', 'credentialData': json.dumps({'algorithm': 'pbkdf2-sha256', 'hashIterations': 210000, 'additionalParameters': {}}),
        'secretData': json.dumps({'value': base64.b64encode(bytes.fromhex(record['hashHex'])).decode(),
            'salt': base64.b64encode(record['saltUtf8'].encode('utf-8')).decode(), 'additionalParameters': {}})}


def amount(value):
    value = str(value)
    if not re.fullmatch('0|[1-9][0-9]{0,17}', value):
        raise ValueError('Invalid integer credit amount')
    return value


def compile_batch(export, review):
    if export['formatVersion'] != 2 or review['sourceDigest'] != export['sourceDigest']:
        raise ValueError('Review does not match the exact source snapshot')
    if review.get('exportDigest')!=export.get('exportDigest') or digest({k:v for k,v in export.items() if k!='exportDigest'})!=export.get('exportDigest'):
        raise ValueError('Private export checksum mismatch')
    issuer = review['targetIssuer'].rstrip('/')
    parsed = urllib.parse.urlsplit(issuer)
    origin(urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, '', '', '')))
    if parsed.path != '/realms/consumer' or parsed.query or parsed.fragment:
        raise ValueError('Target must be the configured platform consumer issuer')
    if not review.get('backupReference') or not review.get('credentialWriteFreezeReference'):
        raise ValueError('Backup and credential write-freeze evidence are required')
    decisions = {row['localUserId']: row for row in review['records']}
    if len(decisions) != len(review['records']) or set(decisions) != {row['localUserId'] for row in export['records']}:
        raise ValueError('Every source record needs exactly one explicit review decision')
    records, exclusions = [], []
    for source in export['records']:
        decision = decisions[source['localUserId']]
        if not decision.get('reason'):
            raise ValueError('Every decision needs an audit reason')
        if decision['action'] == 'EXCLUDE':
            exclusions.append({'localUserId': source['localUserId'], 'reason': decision['reason'], 'disabled': source['disabled'], 'guest': source['guest']})
            continue
        if decision['action'] != 'IMPORT' or source['issues']:
            raise ValueError('Unresolved records cannot be imported; fix and audit a new snapshot')
        policy = decision['policy']
        if policy not in ('EMPLOYEE', 'CONSUMER'):
            raise ValueError('Explicit account policy required')
        membership, staff = None, None
        daily, maximum, daily_max, immediate, delayed, delay = '0', '0', '0', 0, 0, 0
        if policy == 'EMPLOYEE':
            corporate = [i for i in source['identities'] if i['provider'] == 'wecom']
            if len(corporate) != 1 or not decision.get('employeeVerified'):
                raise ValueError('Employee requires one verified corporate identity')
            identity = corporate[0]
            if not review['corporateNamespaceEvidence'].get(identity['tenantId']) or ':' in identity['subject']:
                raise ValueError('Corporate namespace evidence is missing or ambiguous')
            current = source['membership']
            if not current['tier'] or not current['quota'] or not current['requestRule']:
                raise ValueError('Employee tier, quota and request rule require review')
            if current['assignment'] and current['assignment'].get('expires_at') and not current.get('defaultRequestRule'):
                raise ValueError('Temporary membership requires audited default-tier application rules')
            if not current['quota']['enabled'] or current['quota'].get('frozen_at'):
                raise ValueError('Frozen quota needs an explicit reviewed credit policy before migration')
            daily = amount(current['quota']['daily_limit'])
            rule = current['requestRule']
            if rule['enabled']:
                maximum, daily_max = amount(rule['max_auto_amount_per_request']), amount(rule['max_auto_amount_per_day'])
                immediate, delayed, delay = rule['immediate_request_count'], rule['delayed_request_count'], rule['delay_minutes']
            membership = {'orgId': identity['tenantId'], 'provider': 'wecom', 'subject': identity['subject'],
                'enabled': not source['disabled'] and bool(current['quota']['enabled'])}
            staff = {'corpId': identity['tenantId'], 'userId': identity['subject'], 'employeeRef': 'tapcanvas:' + source['localUserId']}
        elif not source['email'] or not source['password']:
            raise ValueError('Consumer requires compatible credentials and an email in this migration')
        subject = str(uuid.uuid5(uuid.NAMESPACE_URL, issuer + '/tapcanvas/' + source['localUserId']))
        existing = decision.get('existingSubject')
        if existing:
            if not decision.get('bindingEvidence') or decision.get('credentialAction') != 'KEEP_VERIFIED_EXISTING':
                raise ValueError('Existing identities require verified ownership and credential verification evidence')
            subject = existing
        item = {'localUserId': source['localUserId'], 'subject': subject, 'existing': bool(existing), 'login': source['login'],
            'email': source['email'], 'emailVerified': bool(decision.get('emailVerified')), 'credential': None if existing else credential(source['password']),
            'credentialSnapshot': source['credentialSnapshot'], 'staff': staff, 'reviewReason': decision['reason'],
            'platform': {'batchId': export['batchId'], 'appId': export['appId'], 'localUserId': source['localUserId'], 'subject': subject,
                'displayName': source['displayName'][:80], 'enabled': not source['disabled'], 'membership': membership,
                'wallet': {'policy': policy, 'dailyCredits': daily, 'initialCredits': amount(decision.get('initialConsumerCredits', '0')) if policy == 'CONSUMER' else '0',
                    'resetTime': export['resetTime'], 'requestEnabled': policy == 'EMPLOYEE', 'maxRequest': maximum, 'maxDailyRequest': daily_max,
                    'immediateCount': immediate, 'delayedCount': delayed, 'delayMinutes': delay}}}
        if policy=='EMPLOYEE' and source['membership']['assignment'] and source['membership']['assignment'].get('expires_at'):
            fallback=source['membership']['defaultRequestRule']; config=copy.deepcopy(item['platform']['wallet'])
            # TapCanvas retains the explicitly configured personal daily limit;
            # membership expiration changes the effective application rule.
            config.update({'maxRequest':amount(fallback['max_auto_amount_per_request']) if fallback['enabled'] else '0',
                'maxDailyRequest':amount(fallback['max_auto_amount_per_day']) if fallback['enabled'] else '0',
                'immediateCount':fallback['immediate_request_count'] if fallback['enabled'] else 0,
                'delayedCount':fallback['delayed_request_count'] if fallback['enabled'] else 0,
                'delayMinutes':fallback['delay_minutes'] if fallback['enabled'] else 0})
            item['schedule']={'requestId':str(uuid.uuid5(uuid.NAMESPACE_URL,issuer+'/rules/'+export['batchId']+'/'+source['localUserId'])),
                'effectiveAt':source['membership']['assignment']['expires_at'],'wallet':config,'reason':'Imported membership expiry: '+decision['reason']}
        item['recordDigest'] = digest(item)
        records.append(item)
    body = {'formatVersion': 1, 'batchId': export['batchId'], 'appId': export['appId'], 'targetIssuer': issuer,
        'sourceDigest': export['sourceDigest'], 'backupReference': review['backupReference'], 'credentialWriteFreezeReference': review['credentialWriteFreezeReference'],
        'records': records, 'exclusions': exclusions, 'business': export['business'], 'inFlight': export['inFlight'], 'pendingSettlements':export.get('pendingSettlements',[])}
    return {**body, 'batchDigest': digest(body)}


class Platform:
    def __init__(self, target):
        self.target = origin(target)
        self.refresh()

    def refresh(self):
        form = urllib.parse.urlencode({'grant_type': 'client_credentials', 'client_id': 'identity-platform-operations', 'client_secret': settings()['platform_operations']}).encode()
        with HTTP.open(urllib.request.Request(BASE + '/realms/consumer/protocol/openid-connect/token', data=form), timeout=10) as response:
            self.token = json.load(response)['access_token']
        self.expires=time.monotonic()+45

    def import_account(self, payload):
        return self.post('/internal/v1/admin/imports/accounts',payload)

    def post(self,path,payload):
        if time.monotonic()>self.expires:self.refresh()
        req = urllib.request.Request(self.target + path, data=json.dumps(payload).encode(),
            headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        with HTTP.open(req, timeout=15) as response:
            return json.load(response)


def ensure_staff(admin, record):
    identity = record['staff']
    if not identity:
        return
    idp = admin.call('GET', '/workforce/identity-provider/instances/wecom-corp')
    if idp['config']['corpId'] != identity['corpId']:
        raise ValueError('Target WeCom corporation differs from the verified source namespace')
    external = identity['corpId'] + ':' + identity['userId']
    # Query broker identity directly; never infer by employee display name or email.
    query = urllib.parse.urlencode({'idpAlias': 'wecom-corp', 'idpUserId': external})
    matches = admin.call('GET', '/workforce/users?' + query)
    if len(matches) > 1:
        raise ValueError('Ambiguous target corporate identity')
    if matches:
        staff = matches[0]
        if not staff.get('enabled') and record['platform']['enabled']:
            raise ValueError('Existing disabled employee cannot be re-enabled by import')
        if not staff.get('attributes',{}).get('employee_ref'):
            raise ValueError('Existing corporate identity has no approved employee reference')
        api=admin.client('workforce','workforce-api')
        roles=admin.call('GET','/workforce/users/'+staff['id']+'/role-mappings/clients/'+api['id']+'/composite')
        if record['platform']['enabled'] and not any(role['name']=='employee' for role in roles):
            raise ValueError('Existing corporate identity is not authorized as an employee')
    else:
        sid = str(uuid.uuid5(uuid.NAMESPACE_URL, BASE + '/workforce/wecom/' + external))
        staged_rows=admin.call('GET','/workforce/users?'+urllib.parse.urlencode({'username':'staff-'+sid,'exact':'true'}))
        if len(staged_rows)>1:
            raise ValueError('Ambiguous staged corporate identity')
        staged=staged_rows[0] if staged_rows else None
        if staged and staged.get('attributes',{}).get('employee_ref') != [identity['employeeRef']]:
            raise ValueError('Staged workforce identity conflict')
        if not staged:
            admin.call('POST', '/workforce/users', {'username': 'staff-' + sid, 'enabled': record['platform']['enabled'],
                'attributes': {'employee_ref': [identity['employeeRef']]}, 'requiredActions': ['CONFIGURE_TOTP']})
        staff = admin.call('GET','/workforce/users?'+urllib.parse.urlencode({'username':'staff-'+sid,'exact':'true'}))[0]
        sid=staff['id']
        admin.call('POST', '/workforce/users/' + sid + '/federated-identity/wecom-corp',
            {'identityProvider': 'wecom-corp', 'userId': external, 'userName': identity['userId']})
        api = admin.client('workforce', 'workforce-api')
        role = admin.call('GET', '/workforce/clients/' + api['id'] + '/roles/employee')
        if record['platform']['enabled']:
            admin.call('POST', '/workforce/users/' + sid + '/role-mappings/clients/' + api['id'], [role])
    path = '/consumer/users/' + record['subject'] + '/federated-identity'
    existing = next((link for link in admin.call('GET', path) if link['identityProvider'] == 'company'), None)
    if existing and existing['userId'] != staff['id']:
        raise ValueError('Corporate link conflicts with existing account; manual verification required')
    if not existing:
        admin.call('POST', path + '/company', {'identityProvider': 'company', 'userId': staff['id'], 'userName': staff['username']})


def apply_batch(batch, expected, portal):
    if not re.fullmatch('[A-Za-z0-9_-]{1,80}',batch['batchId']):
        raise ValueError('Invalid batch identifier')
    body = {key: value for key, value in batch.items() if key != 'batchDigest'}
    if digest(body) != expected or batch['batchDigest'] != expected or batch['targetIssuer'] != BASE + '/realms/consumer':
        raise ValueError('Target or approved batch checksum mismatch')
    if not batch['backupReference'] or not batch['credentialWriteFreezeReference']:
        raise ValueError('Cutover evidence missing')
    admin, platform = Admin(), Platform(portal)
    journal_path = LOCAL / ('migration-' + batch['batchId'] + '.json')
    if journal_path.exists():
        journal = json.loads(journal_path.read_text(encoding='utf-8'))
        if journal['batchDigest'] != expected:
            raise ValueError('Batch identifier already used for other facts')
    else:
        journal = {'batchDigest': expected, 'records': {}}
    for planned in batch['records']:
        record=copy.deepcopy(planned)
        completed=journal['records'].get(record['localUserId'])
        if completed:
            # A completed import is immutable history. Never re-enable an account,
            # restore a deliberately removed corporate link, or replace a changed
            # password when an operator replays the original batch.
            if completed.get('status')!='COMPLETE':
                raise ValueError('Unexpected migration journal state')
            target=admin.call('GET','/consumer/users/'+completed['subject'],missing=True)
            if not target or (not record['existing'] and target.get('attributes',{}).get('migration_digest')!=[record['recordDigest']]):
                raise ValueError('Completed identity no longer matches migration evidence')
            record['platform']['subject']=completed['subject']
            result=platform.import_account(record['platform'])
            if result['userId']!=completed['platformUserId']:
                raise ValueError('Completed platform mapping no longer matches the journal')
            continue
        path = '/consumer/users/' + record['subject']
        if record['existing']:
            target=admin.call('GET',path,missing=True)
        else:
            matches=admin.call('GET','/consumer/users?'+urllib.parse.urlencode({'username':record['login'],'exact':'true'}))
            if len(matches)>1:
                raise ValueError('Target login is ambiguous')
            target=matches[0] if matches else None
        if target and not record['existing']:
            if target.get('attributes', {}).get('migration_digest') != [record['recordDigest']]:
                raise ValueError('Target account exists with different migration facts')
        if not target:
            if record['existing']:
                raise ValueError('Explicit target subject does not exist')
            for query in ({'username': record['login'], 'exact': 'true'}, {'email': record['email'], 'exact': 'true'} if record['email'] else {}):
                if query and admin.call('GET', '/consumer/users?' + urllib.parse.urlencode(query)):
                    raise ValueError('Target login/email conflict; automatic account merging is prohibited')
            payload = {'username': record['login'], 'enabled': False, 'emailVerified': record['emailVerified'],
                'attributes': {'migration_digest': [record['recordDigest']]}}
            if record['email']:
                payload['email'] = record['email']
            if record['credential']:
                payload['credentials'] = [record['credential']]
            admin.call('POST', '/consumer/users', payload)
            matches=admin.call('GET','/consumer/users?'+urllib.parse.urlencode({'username':record['login'],'exact':'true'}))
            if len(matches)!=1 or matches[0].get('attributes',{}).get('migration_digest')!=[record['recordDigest']]:
                raise ValueError('Cannot resolve the newly staged identity')
            target=matches[0]
        elif record['existing'] and not target.get('enabled') and record['platform']['enabled']:
            raise ValueError('Import cannot reactivate an existing disabled platform user')
        # The Admin API creates its own subject. Preserve that subject on replay;
        # a matching login alone never authorizes linking an existing account.
        record['subject']=target['id']
        record['platform']['subject']=target['id']
        path='/consumer/users/'+target['id']
        ensure_staff(admin, record)
        result = platform.import_account(record['platform'])
        if record.get('schedule'):
            platform.post('/internal/v1/admin/wallets/'+result['userId']+'/rule-schedules',record['schedule'])
        # Newly created identity is enabled only after the original product mapping is committed.
        if not record['existing']:
            admin.call('PUT', path, {'enabled': record['platform']['enabled']})
        journal['records'][record['localUserId']] = {'platformUserId': result['userId'], 'subject': record['subject'], 'status': 'COMPLETE'}
        temporary = journal_path.with_suffix('.tmp')
        temporary.write_text(json.dumps(journal, indent=2), encoding='utf-8')
        temporary.replace(journal_path)
    print(json.dumps({'batchId': batch['batchId'], 'completed': len(journal['records']), 'excluded': len(batch['exclusions']), 'newAccountProvisioning': 'unchanged/closed'}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    compile_cmd = sub.add_parser('compile');compile_cmd.add_argument('--export', required=True);compile_cmd.add_argument('--review', required=True);compile_cmd.add_argument('--output', required=True)
    apply_cmd = sub.add_parser('apply');apply_cmd.add_argument('--batch', required=True);apply_cmd.add_argument('--expected-digest', required=True);apply_cmd.add_argument('--portal', type=origin, required=True);apply_cmd.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    if args.command == 'compile':
        batch = compile_batch(json.loads(Path(args.export).read_text(encoding='utf-8')), json.loads(Path(args.review).read_text(encoding='utf-8')))
        output = Path(args.output).resolve()
        if '.local' not in output.parts and '.tmp' not in output.parts:
            raise ValueError('Credential-bearing batches must be in ignored .local or .tmp')
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('x', encoding='utf-8') as stream:
            json.dump(batch, stream, ensure_ascii=False, indent=2)
        print(json.dumps({'batchDigest': batch['batchDigest'], 'records': len(batch['records']), 'exclusions': len(batch['exclusions'])}))
    else:
        batch = json.loads(Path(args.batch).read_text(encoding='utf-8'))
        if batch['batchDigest'] != args.expected_digest or digest({k:v for k,v in batch.items() if k!='batchDigest'}) != args.expected_digest:
            raise ValueError('Batch checksum mismatch')
        if args.execute:
            apply_batch(batch, args.expected_digest, args.portal)
        else:
            print(json.dumps({'mode':'DRY_RUN','records':len(batch['records']),'targetIssuer':batch['targetIssuer'],'batchDigest':batch['batchDigest']}))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        # HTTP bodies can contain credentials; never print arbitrary exception representations.
        detail=str(failure) if isinstance(failure,(ValueError,AdminError)) else ('Platform HTTP '+str(failure.code) if isinstance(failure,urllib.error.HTTPError) else type(failure).__name__)
        print('Migration stopped: '+detail)
        raise SystemExit(1)
