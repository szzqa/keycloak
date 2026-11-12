package local.identity.demo.wallet;

import static local.identity.demo.wallet.WalletTypes.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreditRequests {
    private final WalletStore store;
    public CreditRequests(WalletStore store) { this.store=store; }
    private Map<String,Object> request(UUID user,UUID id) {
        return store.db().sql("SELECT id,amount,status,created_at FROM platform_credit_request WHERE id=:id AND user_id=:user")
            .param("id",id).param("user",user).query((r,i)->Map.<String,Object>of("requestId",r.getString("id"),"amount",r.getString("amount"),
                "status",r.getString("status").toLowerCase(Locale.ROOT),"createdAt",r.getTimestamp("created_at").toInstant().toString()))
            .optional().orElseThrow(()->error(404,"request_not_found"));
    }
    public Map<String,Object> history(UUID user) {
        var ids=store.db().sql("SELECT id FROM platform_credit_request WHERE user_id=:user ORDER BY created_at DESC LIMIT 50")
            .param("user",user).query(UUID.class).list();
        return Map.of("items",ids.stream().map(id->request(user,id)).toList());
    }
    @Transactional
    public Map<String,Object> apply(UUID user,CreditRequest input) {
        if(input.requestId()==null) throw error(400,"request_id_required");
        long amount=credits(input.amount()); bounded(input.reason(),1000);
        if(amount==0) throw error(400,"positive_amount_required");
        Rules rules=store.lock(user); store.requireActive(rules);
        if(rules.policy()!=Policy.EMPLOYEE || !rules.requests()) throw error(403,"credit_request_not_allowed");
        var old=store.db().sql("SELECT user_id,amount,reason FROM platform_credit_request WHERE id=:id")
            .param("id",input.requestId()).query().listOfRows().stream().findFirst();
        if(old.isPresent()) {
            var row=old.get();
            if(!user.equals(row.get("user_id")) || ((Number)row.get("amount")).longValue()!=amount || !input.reason().equals(row.get("reason")))
                throw error(409,"request_facts_conflict");
            return request(user,input.requestId());
        }
        var day=store.now().atZone(ZONE).toLocalDate();
        // Count all attempts in the day, including pending approvals, across all products.
        var totals=store.db().sql("SELECT count(*) AS count,COALESCE(sum(amount) FILTER (WHERE decision_mode IN ('IMMEDIATE','DELAYED')),0) AS total FROM platform_credit_request WHERE user_id=:user AND calendar_date=:day")
            .param("user",user).param("day",day).query().singleRow();
        long sequence=((Number)totals.get("count")).longValue()+1;
        long total=((Number)totals.get("total")).longValue();
        boolean eligible=amount<=rules.maxRequest() && amount<=rules.maxDaily()-total;
        String mode=eligible && sequence<=rules.immediate()?"IMMEDIATE":eligible && sequence<=((long)rules.immediate()+rules.delayed())?"DELAYED":"MANUAL";
        java.time.OffsetDateTime scheduled=mode.equals("DELAYED")?store.now().plusSeconds((long)rules.delayMinutes()*60).atOffset(java.time.ZoneOffset.UTC):null;
        store.db().sql("""
            INSERT INTO platform_credit_request(id,user_id,amount,reason,calendar_date,sequence_in_day,status,decision_mode,rule_snapshot,scheduled_at,epoch)
            VALUES(:id,:user,:amount,:reason,:day,:seq,'PROCESSING',:mode,:rules,:scheduled,:epoch)
            """).param("id",input.requestId()).param("user",user).param("amount",amount).param("reason",input.reason()).param("day",day)
            .param("seq",sequence).param("mode",mode).param("rules",rules.toString()).param("scheduled",scheduled,java.sql.Types.TIMESTAMP_WITH_TIMEZONE).param("epoch",rules.epoch()).update();
        if(mode.equals("IMMEDIATE")) grant(rules,input.requestId(),amount,"system","automatic");
        return request(user,input.requestId());
    }
    private void grant(Rules rules,UUID id,long amount,String actor,String note) {
        Bucket bucket=store.current(rules);
        store.change(rules.user(),bucket,amount,0,"request:"+id,"REQUEST_GRANT",null,null,id.toString());
        store.db().sql("UPDATE platform_credit_request SET status='APPROVED',granted_bucket=:bucket,reviewed_by=:actor,review_note=:note,updated_at=now() WHERE id=:id")
            .param("bucket",bucket.id()).param("actor",actor).param("note",note).param("id",id).update();
    }
    @Transactional
    public void decide(UUID id,boolean approve,String actor,String note) {
        bounded(actor,255); bounded(note,1000);
        UUID user=store.db().sql("SELECT user_id FROM platform_credit_request WHERE id=:id").param("id",id).query(UUID.class)
            .optional().orElseThrow(()->error(404,"request_not_found"));
        Rules rules=store.lock(user);
        var row=store.db().sql("SELECT * FROM platform_credit_request WHERE id=:id FOR UPDATE").param("id",id).query().singleRow();
        String target=approve?"APPROVED":"REJECTED";
        if(!row.get("status").equals("PROCESSING")) {
            if(row.get("status").equals(target) && actor.equals(row.get("reviewed_by")) && note.equals(row.get("review_note"))) return;
            throw error(409,"request_already_decided");
        }
        if(approve) {
            store.requireActive(rules);
            if(rules.policy()!=Policy.EMPLOYEE || ((Number)row.get("epoch")).intValue()!=rules.epoch()) throw error(409,"request_policy_changed");
            grant(rules,id,((Number)row.get("amount")).longValue(),actor,note);
        } else store.db().sql("UPDATE platform_credit_request SET status='REJECTED',reviewed_by=:actor,review_note=:note,updated_at=now() WHERE id=:id")
            .param("actor",actor).param("note",note).param("id",id).update();
    }
    public List<UUID> due() {
        return store.db().sql("""
            SELECT r.id FROM platform_credit_request r JOIN platform_wallet w ON w.user_id=r.user_id
            JOIN platform_membership m ON m.user_id=r.user_id JOIN platform_user u ON u.id=r.user_id
            WHERE r.status='PROCESSING' AND r.decision_mode='DELAYED' AND r.scheduled_at<=:now
              AND w.enabled AND m.enabled AND m.upstream_active AND u.enabled AND w.policy='EMPLOYEE' AND r.epoch=w.epoch
            ORDER BY r.scheduled_at LIMIT 100
            """).param("now",Timestamp.from(store.now())).query(UUID.class).list();
    }
    public UUID owner(UUID id) {
        return store.db().sql("SELECT user_id FROM platform_credit_request WHERE id=:id").param("id",id).query(UUID.class)
            .optional().orElseThrow(()->error(404,"request_not_found"));
    }
}
