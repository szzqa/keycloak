import base64
import copy
import hashlib
import unittest
from unittest.mock import patch
from tempfile import TemporaryDirectory
from pathlib import Path
import json
from account_migration import apply_batch
from account_migration import compile_batch, credential, digest
from credit_cutover import compile_batch as compile_opening


class MigrationTests(unittest.TestCase):
    def fixture(self):
        salt='3b2eb8bb-d894-4ef6-b75d-a4c26c26364d'
        password={'algorithm':'pbkdf2-sha256','iterations':210000,'saltUtf8':salt,'hashHex':hashlib.pbkdf2_hmac('sha256','密码🔐'.encode(),salt.encode(),210000).hex()}
        export={'formatVersion':2,'sourceDigest':'a'*64,'batchId':'test-import','appId':'tapcanvas','resetTime':'00:00:00','business':{},'inFlight':[],
            'records':[{'localUserId':'old-id','login':'original@example.test','email':'original@example.test','displayName':'原账号','disabled':False,'guest':False,'issues':[],
                'password':password,'credentialSnapshot':'b'*64,'identities':[],'membership':{}}]}
        export['exportDigest']=digest(export)
        review={'sourceDigest':export['sourceDigest'],'exportDigest':export['exportDigest'],'targetIssuer':'http://127.0.0.1:8280/realms/consumer',
            'backupReference':'fixture-backup','credentialWriteFreezeReference':'fixture-freeze','corporateNamespaceEvidence':{},
            'records':[{'localUserId':'old-id','action':'IMPORT','policy':'CONSUMER','reason':'verified fixture','emailVerified':True,'initialConsumerCredits':'0'}]}
        return export,review

    def test_utf8_salt_is_preserved_as_bytes_not_decoded_uuid(self):
        export,_=self.fixture();data=credential(export['records'][0]['password'])
        import json
        secret=json.loads(data['secretData']);self.assertEqual(base64.b64decode(secret['salt']),export['records'][0]['password']['saltUtf8'].encode())
        self.assertEqual(base64.b64decode(secret['value']).hex(),export['records'][0]['password']['hashHex'])

    def test_compile_preserves_original_product_id_and_does_not_infer_gifts(self):
        export,review=self.fixture();batch=compile_batch(export,review)
        self.assertEqual(batch['records'][0]['platform']['localUserId'],'old-id')
        self.assertEqual(batch['records'][0]['platform']['wallet']['initialCredits'],'0')
        self.assertEqual(batch,compile_batch(export,review))

    def test_tampered_export_is_rejected_even_with_unchanged_source_digest(self):
        export,review=self.fixture();export['records'][0]['password']['hashHex']='f'*64
        with self.assertRaisesRegex(ValueError,'checksum'):compile_batch(export,review)

    def test_existing_subject_requires_ownership_and_credential_evidence(self):
        export,review=self.fixture();review['records'][0]['existingSubject']='verified-existing-subject'
        with self.assertRaises(ValueError):compile_batch(export,review)
        review['records'][0].update({'bindingEvidence':'independent ownership proof','credentialAction':'KEEP_VERIFIED_EXISTING'})
        item=compile_batch(export,review)['records'][0];self.assertIsNone(item['credential']);self.assertEqual(item['subject'],'verified-existing-subject')

    def test_unresolved_conflict_or_missing_decision_cannot_import(self):
        export,review=self.fixture();review['records']=[]
        with self.assertRaises(ValueError):compile_batch(export,review)
        export,review=self.fixture();export['records'][0]['issues']=['duplicate_email'];export['exportDigest']=digest({k:v for k,v in export.items() if k!='exportDigest'});review['exportDigest']=export['exportDigest']
        with self.assertRaises(ValueError):compile_batch(export,review)

    def test_opening_does_not_carry_balances_and_requires_all_freeze_evidence(self):
        export,review=self.fixture();accounts=compile_batch(export,review)
        journal={'batchDigest':accounts['batchDigest'],'records':{'old-id':{'platformUserId':'00000000-0000-4000-8000-000000000001','status':'COMPLETE'}}}
        opening={'batchId':'credit-test','portalOrigin':'http://127.0.0.1:8281','sourceDigest':export['sourceDigest'],'accountBatchDigest':accounts['batchDigest'],
            'backupReference':'backup','paidTaskFreezeReference':'freeze','legacyGrantFreezeReference':'stopped','credentialReconciliationReference':'verified',
            'records':[{'localUserId':'old-id','action':'OPEN','reason':'reviewed'}]}
        batch=compile_opening(accounts,journal,export,opening);self.assertEqual(batch['legacyBalanceCarryover'],'0')
        invalid=copy.deepcopy(opening);invalid['paidTaskFreezeReference']=''
        with self.assertRaises(ValueError):compile_opening(accounts,journal,export,invalid)

    def test_completed_replay_preserves_later_disable_and_password_changes(self):
        export,review=self.fixture();batch=compile_batch(export,review);record=batch['records'][0]
        subject='existing-actual-subject';user='00000000-0000-4000-8000-000000000001'
        class ReadOnlyAdmin:
            def call(self,method,path,**kwargs):
                if method!='GET' or path!='/consumer/users/'+subject:raise AssertionError('Replay must not mutate identity or look up a renamed login')
                return {'id':subject,'enabled':False,'username':'renamed-after-import','attributes':{'migration_digest':[record['recordDigest']]}}
        class ReplayPlatform:
            def __init__(self,target):pass
            def import_account(self,body):
                if body['subject']!=subject:raise AssertionError('Actual subject must be preserved')
                return {'userId':user,'status':'COMPLETE'}
        with TemporaryDirectory() as directory:
            path=Path(directory)
            (path/('migration-'+batch['batchId']+'.json')).write_text(json.dumps({'batchDigest':batch['batchDigest'],'records':{'old-id':{'subject':subject,'platformUserId':user,'status':'COMPLETE'}}}),encoding='utf-8')
            with patch('account_migration.LOCAL',path),patch('account_migration.BASE','http://127.0.0.1:8280'),patch('account_migration.Admin',ReadOnlyAdmin),patch('account_migration.Platform',ReplayPlatform):
                apply_batch(batch,batch['batchDigest'],'http://127.0.0.1:8281')

if __name__=='__main__':unittest.main()
