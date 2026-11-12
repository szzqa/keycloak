"""Compile and apply a reviewed credit-opening batch. Never carries legacy balances."""
import argparse
import json
import re
import uuid
from pathlib import Path
from account_migration import digest, Platform
from identity_admin import BASE
from platform_setup import origin


def compile_batch(accounts,journal,snapshot,review):
    if digest({k:v for k,v in snapshot.items() if k!='exportDigest'})!=snapshot.get('exportDigest'):
        raise ValueError('Final snapshot checksum mismatch')
    if digest({k:v for k,v in accounts.items() if k!='batchDigest'})!=accounts['batchDigest'] or journal['batchDigest']!=accounts['batchDigest']:
        raise ValueError('Account batch/journal checksum mismatch')
    if review['accountBatchDigest']!=accounts['batchDigest'] or review['sourceDigest']!=snapshot['sourceDigest']:
        raise ValueError('Review must cover the account batch and final cutover snapshot')
    if not re.fullmatch('[A-Za-z0-9_-]{1,80}',review['batchId']):raise ValueError('Invalid opening batch identifier')
    for field in ('backupReference','paidTaskFreezeReference','legacyGrantFreezeReference','credentialReconciliationReference'):
        if not review.get(field):raise ValueError('Cutover evidence missing: '+field)
    if snapshot.get('pendingSettlements'):raise ValueError('Existing platform settlements require reconciliation before a new cutover')
    expected={r['localUserId']:r for r in accounts['records']}
    decisions={r['localUserId']:r for r in review['records']}
    if len(decisions)!=len(review['records']) or set(decisions)!=set(expected):raise ValueError('Every migrated account needs an opening decision')
    disposition=review.get('inFlightDisposition',{})
    for task in snapshot['inFlight']:
        key=task['user_id']+':'+task['task_id']
        if task['creditAuthority']!='LOCAL' or disposition.get(key)!='KEEP_LOCAL':raise ValueError('Every unfinished legacy task must explicitly remain with its original ledger')
    records,excluded=[],[]
    for local,decision in decisions.items():
        if not decision.get('reason'):raise ValueError('Opening decision requires a reason')
        if decision['action']=='EXCLUDE':excluded.append({'localUserId':local,'reason':decision['reason']});continue
        source=expected[local]; completed=journal['records'].get(local)
        if decision['action']!='OPEN' or not source['platform']['enabled'] or not completed or completed['status']!='COMPLETE':raise ValueError('Only enabled, completely migrated identities may open credits')
        records.append({'platformUserId':completed['platformUserId'],'localUserId':local,'request':{
            'requestId':str(uuid.uuid5(uuid.NAMESPACE_URL,accounts['targetIssuer']+'/credit-opening/'+review['batchId']+'/'+local)),
            'batchId':review['batchId'],'sourceDigest':snapshot['sourceDigest'],'reason':decision['reason']}})
    body={'formatVersion':1,'batchId':review['batchId'],'targetIssuer':accounts['targetIssuer'],'portalOrigin':origin(review['portalOrigin']),
        'accountBatchDigest':accounts['batchDigest'],'review':review,'records':records,'excluded':excluded,
        'inFlight':snapshot['inFlight'],'business':snapshot['business'],'legacyBalanceCarryover':'0'}
    return {**body,'batchDigest':digest(body)}


def apply_batch(batch,approved,portal,execute=False):
    if batch['batchDigest']!=approved or digest({k:v for k,v in batch.items() if k!='batchDigest'})!=approved:raise ValueError('Approved opening checksum mismatch')
    if batch['targetIssuer']!=BASE+'/realms/consumer' or origin(portal)!=batch['portalOrigin']:raise ValueError('Opening target mismatch')
    if execute:
        platform=Platform(portal)
        for record in batch['records']:
            platform.post('/internal/v1/admin/wallets/'+str(uuid.UUID(record['platformUserId']))+'/open',record['request'])
    return {'mode':'APPLIED' if execute else 'DRY_RUN','batchId':batch['batchId'],'records':len(batch['records']),'legacyBalanceCarryover':'0'}


def read(path):return json.loads(Path(path).read_text(encoding='utf-8'))
def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    compile_cmd=sub.add_parser('compile')
    for flag in ('accounts','journal','snapshot','review','output'):compile_cmd.add_argument('--'+flag,required=True)
    apply_cmd=sub.add_parser('apply')
    for flag in ('batch','expected-digest','portal'):apply_cmd.add_argument('--'+flag,required=True)
    apply_cmd.add_argument('--execute',action='store_true');args=parser.parse_args()
    if args.command=='compile':
        batch=compile_batch(read(args.accounts),read(args.journal),read(args.snapshot),read(args.review));path=Path(args.output).resolve()
        if not {'.local','.tmp'}.intersection(path.parts):raise ValueError('Cutover batches must be stored in an ignored local directory')
        with path.open('x',encoding='utf-8') as stream:json.dump(batch,stream,ensure_ascii=False,indent=2)
        print(json.dumps({'batchDigest':batch['batchDigest'],'records':len(batch['records'])}))
    else:print(json.dumps(apply_batch(read(args.batch),args.expected_digest,args.portal,args.execute)))
if __name__=='__main__':
    try:main()
    except Exception as failure:
        print('Credit opening stopped: '+(str(failure) if isinstance(failure,ValueError) else type(failure).__name__));raise SystemExit(1)
