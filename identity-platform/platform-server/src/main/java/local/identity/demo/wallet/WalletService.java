package local.identity.demo.wallet;

import static local.identity.demo.wallet.WalletTypes.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {
    private final WalletStore store;
    public WalletService(WalletStore store) { this.store = store; }
    public record Opening(UUID requestId,String batchId,String sourceDigest,String reason) { }
    @Transactional public void open(UUID user,String actor,Opening input) {
        if(input.requestId()==null || input.sourceDigest()==null || !input.sourceDigest().matches("[a-f0-9]{64}"))throw error(400,"opening_batch_required");
        bounded(input.batchId(),191);bounded(input.reason(),1000);Rules rules=store.lock(user);
        var prior=store.db().sql("SELECT user_id,epoch,batch_id,source_digest,reason FROM platform_credit_opening WHERE request_id=:id")
            .param("id",input.requestId()).query().listOfRows();
        if(!prior.isEmpty()) {
            var row=prior.getFirst();
            if(!row.get("user_id").equals(user) || !row.get("epoch").equals(rules.epoch()) || !row.get("batch_id").equals(input.batchId()) || !row.get("source_digest").equals(input.sourceDigest()) || !row.get("reason").equals(input.reason()))throw error(409,"opening_batch_conflict");
            return;
        }
        store.requireIdentityActive(rules);
        if(store.creditActive(user))throw error(409,"credits_already_opened");
        store.db().sql("INSERT INTO platform_credit_opening(request_id,user_id,epoch,batch_id,source_digest,actor,reason) VALUES(:id,:user,:epoch,:batch,:digest,:actor,:reason)")
            .param("id",input.requestId()).param("user",user).param("epoch",rules.epoch()).param("batch",input.batchId()).param("digest",input.sourceDigest()).param("actor",actor).param("reason",input.reason()).update();
        store.db().sql("UPDATE platform_wallet SET credit_active=true WHERE user_id=:id").param("id",user).update();
        store.current(rules);
    }
    public record Operation(String operationId, UUID userId, UUID bucketId, String mode, String requestDigest,
                            String context, String amount, String status, String due, String charged,
                            String uncollected, String evidence) { }
    public record Refund(UUID requestId,String amount,String reason) { }
    public record Cancellation(Reserve reservation,String evidence) { }
    @Transactional public void cancelUnsubmitted(String app,UUID user,Cancellation input) {
        if(input.reservation()==null)throw error(400,"reservation_required");
        var request=input.reservation();request.validate();bounded(input.evidence(),1000);store.lock(user);
        String facts=user+":"+request;
        var old=store.db().sql("SELECT facts,evidence FROM platform_operation_cancellation WHERE app_id=:app AND operation_id=:op")
            .param("app",app).param("op",request.operationId()).query().listOfRows();
        if(!old.isEmpty()) {
            if(facts.equals(old.getFirst().get("facts")) && input.evidence().equals(old.getFirst().get("evidence")))return;
            throw error(409,"cancellation_facts_conflict");
        }
        var existing=find(app,request.operationId());
        if(existing.isPresent()) {
            var op=existing.get();
            if(!op.userId().equals(user) || !op.mode().equals(request.mode().name()) || !op.amount().equals(request.amount()) || !op.requestDigest().equals(request.requestDigest()) || !op.context().equals(request.context()))throw error(409,"operation_facts_conflict");
            release(app,request.operationId(),new Release(input.evidence()));
        }
        store.db().sql("INSERT INTO platform_operation_cancellation(app_id,operation_id,user_id,facts,evidence) VALUES(:app,:op,:user,:facts,:evidence)")
            .param("app",app).param("op",request.operationId()).param("user",user).param("facts",facts).param("evidence",input.evidence()).update();
    }
    @Transactional
    public void refund(String app,String operation,Refund input,String actor) {
        if(input.requestId()==null)throw error(400,"request_id_required");
        long amount=credits(input.amount());if(amount==0)throw error(400,"positive_amount_required");bounded(input.reason(),1000);bounded(actor,255);
        var op=query(app,operation);var rules=store.lock(op.userId());
        var old=store.db().sql("SELECT * FROM platform_wallet_refund WHERE app_id=:app AND refund_id=:id").param("app",app).param("id",input.requestId()).query().listOfRows();
        if(!old.isEmpty()) {
            var row=old.getFirst();
            if(operation.equals(row.get("operation_id")) && amount==((Number)row.get("amount")).longValue() && input.reason().equals(row.get("reason")) && actor.equals(row.get("actor")))return;
            throw error(409,"refund_facts_conflict");
        }
        if(!op.status().equals("SETTLED"))throw error(409,"refund_requires_settlement");
        long refunded=store.db().sql("SELECT COALESCE(sum(amount),0) FROM platform_wallet_refund WHERE app_id=:app AND operation_id=:op")
            .param("app",app).param("op",operation).query(Long.class).single();
        if(amount>Long.parseLong(op.charged())-refunded)throw error(409,"refund_exceeds_charge");
        var bucket=store.bucket(op.bucketId());
        var after=store.change(op.userId(),bucket,amount,0,"refund:"+app+":"+input.requestId(),"REFUND",app,operation,input.reason());
        int epoch=store.db().sql("SELECT epoch FROM platform_wallet_bucket WHERE id=:id").param("id",bucket.id()).query(Integer.class).single();
        if(epoch!=rules.epoch())store.change(op.userId(),after,-amount,0,"refund-archive:"+input.requestId(),"ARCHIVE",app,operation,"refund_to_archived_policy");
        else store.expireReleased(op.userId(),after,"refund-expiry:"+input.requestId());
        store.db().sql("INSERT INTO platform_wallet_refund(app_id,refund_id,operation_id,amount,reason,actor) VALUES(:app,:id,:op,:amount,:reason,:actor)")
            .param("app",app).param("id",input.requestId()).param("op",operation).param("amount",amount).param("reason",input.reason()).param("actor",actor).update();
    }
    private Optional<Operation> find(String app, String operation) {
        return store.db().sql("SELECT * FROM platform_wallet_operation WHERE app_id=:app AND operation_id=:op")
            .param("app",app).param("op",operation).query((r,i)->new Operation(operation,r.getObject("user_id",UUID.class),
                r.getObject("bucket_id",UUID.class),r.getString("mode"),r.getString("request_digest"),r.getString("context"),
                r.getString("amount"),r.getString("status"),r.getString("due"),r.getString("charged"),r.getString("uncollected"),r.getString("evidence"))).optional();
    }
    public Operation query(String app, String operation) {
        bounded(operation,191);
        return find(app,operation).orElseThrow(()->error(404,"operation_not_found"));
    }
    @Transactional
    public Map<String,Object> balance(UUID user) {
        Rules rules=store.lock(user); store.requireIdentityActive(rules);
        if(!store.creditActive(user)) {
            var pending=new LinkedHashMap<String,Object>();pending.put("policy",rules.policy());pending.put("active",false);
            for(String key:List.of("balance","available","reserved","totalEarned","totalSpent"))pending.put(key,"0");
            pending.put("resetAt",null);pending.put("canApply",false);pending.put("updatedAt",store.now().toString());return pending;
        }
        Bucket bucket=store.current(rules);
        var frozen=store.db().sql("SELECT COALESCE(SUM(reserved),0) FROM platform_wallet_bucket WHERE user_id=:user")
            .param("user",user).query(java.math.BigDecimal.class).single().toBigIntegerExact();
        var result=new LinkedHashMap<String,Object>();
        result.put("active",true);
        result.put("policy",rules.policy()); result.put("available",Long.toString(bucket.balance()-bucket.reserved()));
        result.put("reserved",frozen.toString());
        result.put("balance",java.math.BigInteger.valueOf(bucket.balance()-bucket.reserved()).add(frozen).toString());
        result.put("resetAt",bucket.expires()==null?null:bucket.expires().toString());
        result.put("canApply",rules.policy()==Policy.EMPLOYEE && rules.requests());
        var totals=store.db().sql("""
            SELECT COALESCE(sum(amount) FILTER (WHERE amount>0 AND kind<>'REFUND'),0) AS earned,
                   COALESCE(-sum(amount) FILTER (WHERE kind IN ('SETTLE','REFUND')),0) AS spent,
                   COALESCE(max(created_at),now()) AS updated
            FROM platform_wallet_ledger WHERE bucket_id=:bucket
            """).param("bucket",bucket.id()).query().singleRow();
        result.put("totalEarned",totals.get("earned").toString());result.put("totalSpent",totals.get("spent").toString());
        result.put("updatedAt",((java.sql.Timestamp)totals.get("updated")).toInstant().toString());
        return result;
    }
    public Map<String,Object> ledger(UUID user, Long before) {
        if(before!=null && before<=0) throw error(400,"invalid_cursor");
        var rows=store.db().sql("""
            SELECT * FROM platform_wallet_ledger WHERE user_id=:user AND sequence<:before ORDER BY sequence DESC LIMIT 51
            """).param("user",user).param("before",before==null?Long.MAX_VALUE:before).query((r,i)->{
                var row=new LinkedHashMap<String,Object>();
                row.put("sequence",Long.toString(r.getLong("sequence"))); row.put("kind",r.getString("kind"));
                row.put("appId",r.getString("app_id")); row.put("operationId",r.getString("operation_id"));
                row.put("amount",r.getString("amount")); row.put("reservedDelta",r.getString("reserved_delta"));
                row.put("balanceAfter",r.getString("balance_after")); row.put("createdAt",r.getTimestamp("created_at").toInstant().toString());
                return row;
            }).list();
        var response=new LinkedHashMap<String,Object>();
        response.put("items",rows.subList(0,Math.min(50,rows.size())));
        response.put("nextCursor",rows.size()>50?rows.get(49).get("sequence"):null); return response;
    }
    public List<UUID> refreshDue() {
        return store.db().sql("""
            SELECT w.user_id FROM platform_wallet w JOIN platform_user u ON u.id=w.user_id
            JOIN platform_membership m ON m.user_id=w.user_id
            WHERE w.policy='EMPLOYEE' AND w.enabled AND w.credit_active AND u.enabled AND m.enabled AND m.upstream_active
            AND NOT EXISTS(SELECT 1 FROM platform_wallet_bucket b WHERE b.user_id=w.user_id AND b.epoch=w.epoch
              AND b.period=((:now AT TIME ZONE 'Asia/Shanghai')::date-CASE WHEN (:now AT TIME ZONE 'Asia/Shanghai')::time<w.reset_time THEN 1 ELSE 0 END)::text)
            ORDER BY w.user_id LIMIT 100
            """).param("now",java.sql.Timestamp.from(store.now())).query(UUID.class).list();
    }
    @Transactional
    public Operation reserve(String app, UUID user, Reserve input) {
        long amount=input.validate();
        Rules rules=store.lock(user); store.requireActive(rules);
        var previous=find(app,input.operationId());
        if(previous.isPresent()) {
            var old=previous.get();
            if(!old.userId().equals(user) || !old.mode().equals(input.mode().name()) || !old.amount().equals(input.amount())
                || !old.requestDigest().equals(input.requestDigest()) || !old.context().equals(input.context()))
                throw error(409,"operation_facts_conflict");
            return old;
        }
        if(store.db().sql("SELECT EXISTS(SELECT 1 FROM platform_operation_cancellation WHERE app_id=:app AND operation_id=:op)")
            .param("app",app).param("op",input.operationId()).query(Boolean.class).single())throw error(409,"operation_cancelled_before_submission");
        Bucket bucket=store.current(rules);
        long available=bucket.balance()-bucket.reserved();
        if(amount>available || input.mode()==Mode.TOKEN && available<=0) throw error(409,"insufficient_credits");
        store.db().sql("""
            INSERT INTO platform_wallet_operation(app_id,operation_id,user_id,bucket_id,mode,request_digest,context,amount,status)
            VALUES(:app,:op,:user,:bucket,:mode,:digest,:context,:amount,'RESERVED')
            """).param("app",app).param("op",input.operationId()).param("user",user).param("bucket",bucket.id())
            .param("mode",input.mode().name()).param("digest",input.requestDigest()).param("context",input.context()).param("amount",amount).update();
        store.change(user,bucket,0,amount,"reserve:"+app+":"+input.operationId(),"RESERVE",app,input.operationId(),input.requestDigest());
        return query(app,input.operationId());
    }
    @Transactional
    public Operation settle(String app,String id,Settlement input) {
        long due=credits(input.amount()); bounded(input.evidence(),4000);
        var original=query(app,id);
        store.lock(original.userId()); // Existing authorization may settle after membership revocation.
        var op=query(app,id);
        if(!op.status().equals("RESERVED")) {
            if(op.status().equals("SETTLED") && op.due().equals(input.amount()) && Objects.equals(op.evidence(),input.evidence())) return op;
            throw error(409,"terminal_facts_conflict");
        }
        long reserved=Long.parseLong(op.amount());
        Bucket bucket=store.bucket(op.bucketId());
        if(op.mode().equals("FIXED") && due>reserved) throw error(409,"settlement_exceeds_reservation");
        long charged=op.mode().equals("TOKEN")?Math.min(due,bucket.balance()-bucket.reserved()):due;
        var after=store.change(op.userId(),bucket,-charged,-reserved,"settle:"+app+":"+id,"SETTLE",app,id,input.evidence());
        store.db().sql("""
            UPDATE platform_wallet_operation SET status='SETTLED',due=:due,charged=:charged,uncollected=:gap,evidence=:evidence,updated_at=now()
            WHERE app_id=:app AND operation_id=:op
            """).param("due",due).param("charged",charged).param("gap",due-charged).param("evidence",input.evidence()).param("app",app).param("op",id).update();
        store.expireReleased(op.userId(),after,"expire-settle:"+app+":"+id);
        return query(app,id);
    }
    @Transactional
    public Operation release(String app,String id,Release input) {
        bounded(input.evidence(),4000);
        var original=query(app,id); store.lock(original.userId()); var op=query(app,id);
        if(!op.status().equals("RESERVED")) {
            if(op.status().equals("RELEASED") && Objects.equals(op.evidence(),input.evidence())) return op;
            throw error(409,"terminal_facts_conflict");
        }
        var after=store.change(op.userId(),store.bucket(op.bucketId()),0,-Long.parseLong(op.amount()),"release:"+app+":"+id,"RELEASE",app,id,input.evidence());
        store.db().sql("UPDATE platform_wallet_operation SET status='RELEASED',evidence=:evidence,updated_at=now() WHERE app_id=:app AND operation_id=:op")
            .param("evidence",input.evidence()).param("app",app).param("op",id).update();
        store.expireReleased(op.userId(),after,"expire-release:"+app+":"+id);
        return query(app,id);
    }
}
