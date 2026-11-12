package local.identity.demo.wallet;

import static local.identity.demo.wallet.WalletTypes.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lock order is wallet -> bucket -> operation/request, for all writers. */
@Repository
public class WalletStore {
    private final JdbcClient db;
    private final Clock clock;
    public WalletStore(JdbcClient db, Clock clock) { this.db = db; this.clock = clock; }
    public JdbcClient db() { return db; }
    public Instant now() { return clock.instant(); }
    public Rules lock(UUID user) {
        Rules rules=db.sql("SELECT * FROM platform_wallet WHERE user_id=:user FOR UPDATE").param("user", user)
            .query((r,i) -> new Rules(user, Policy.valueOf(r.getString("policy")), r.getBoolean("enabled"), r.getInt("epoch"),
                r.getLong("daily_credits"), r.getLong("initial_credits"), r.getTime("reset_time").toLocalTime(),
                r.getBoolean("request_enabled"), r.getLong("max_request"), r.getLong("max_daily_request"),
                r.getInt("immediate_count"), r.getInt("delayed_count"), r.getInt("delay_minutes")))
            .optional().orElseThrow(() -> error(409, "wallet_not_provisioned"));
        if(rules.policy()!=Policy.EMPLOYEE)return rules;
        return db.sql("SELECT * FROM platform_wallet_rule_schedule WHERE user_id=:user AND epoch=:epoch AND active AND effective_at<=:now ORDER BY effective_at DESC LIMIT 1")
            .param("user",user).param("epoch",rules.epoch()).param("now",Timestamp.from(now()))
            .query((r,i)->new Rules(user,rules.policy(),rules.enabled(),rules.epoch(),r.getLong("daily_credits"),rules.initial(),rules.resetTime(),
                r.getBoolean("request_enabled"),r.getLong("max_request"),r.getLong("max_daily_request"),r.getInt("immediate_count"),r.getInt("delayed_count"),r.getInt("delay_minutes")))
            .optional().orElse(rules);
    }
    public void requireActive(Rules rules) {
        requireIdentityActive(rules);
        if(!creditActive(rules.user()))throw error(409,"credits_not_opened");
    }
    public boolean creditActive(UUID user) {return db.sql("SELECT credit_active FROM platform_wallet WHERE user_id=:id").param("id",user).query(Boolean.class).single();}
    public void requireIdentityActive(Rules rules) {
        boolean enabled = db.sql("SELECT enabled FROM platform_user WHERE id=:id").param("id",rules.user()).query(Boolean.class).single();
        if (!enabled || !rules.enabled()) throw error(403, "account_disabled");
        if (rules.policy() == Policy.EMPLOYEE && !db.sql("SELECT EXISTS(SELECT 1 FROM platform_membership WHERE user_id=:id AND enabled AND upstream_active)")
            .param("id",rules.user()).query(Boolean.class).single()) throw error(403, "employee_inactive");
    }
    public Bucket current(Rules rules) {
        String period = rules.period(now());
        var existing = db.sql("SELECT id FROM platform_wallet_bucket WHERE user_id=:user AND epoch=:epoch AND period=:period")
            .param("user",rules.user()).param("epoch",rules.epoch()).param("period",period).query(UUID.class).optional();
        if (existing.isPresent()) return bucket(existing.get());
        // Expire only unreserved credits. Late settlements keep their original authorization bucket.
        var expired = db.sql("SELECT id FROM platform_wallet_bucket b WHERE user_id=:user AND epoch=:epoch AND expires_at<=:now AND balance>reserved AND NOT EXISTS(SELECT 1 FROM platform_wallet_operation o WHERE o.bucket_id=b.id AND o.mode='TOKEN' AND o.status='RESERVED')")
            .param("user",rules.user()).param("epoch",rules.epoch()).param("now",Timestamp.from(now())).query(UUID.class).list();
        for (UUID id : expired) {
            var old = bucket(id);
            change(rules.user(), old, -(old.balance()-old.reserved()), 0, "expire:"+id, "EXPIRE", null, null, "period_expired");
        }
        UUID id = UUID.randomUUID();
        Instant expires = rules.expires(now());
        db.sql("INSERT INTO platform_wallet_bucket(id,user_id,epoch,period,expires_at,balance) VALUES(:id,:user,:epoch,:period,:expires,0)")
            .param("id",id).param("user",rules.user()).param("epoch",rules.epoch()).param("period",period)
            .param("expires",expires == null ? null : expires.atOffset(ZoneOffset.UTC),java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
        long grant = rules.policy() == Policy.EMPLOYEE ? rules.daily() : rules.initial();
        return change(rules.user(), bucket(id), grant, 0, "opening:"+id, rules.policy()==Policy.EMPLOYEE ? "DAILY_GRANT" : "INITIAL_GRANT", null,null,"epoch:"+rules.epoch());
    }
    public Bucket bucket(UUID id) {
        return db.sql("SELECT * FROM platform_wallet_bucket WHERE id=:id FOR UPDATE").param("id",id)
            .query((r,i)->new Bucket(id,r.getLong("balance"),r.getLong("reserved"),r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant())).single();
    }
    public Bucket change(UUID user, Bucket before, long amount, long reservedDelta, String key, String kind, String app, String operation, String detail) {
        long balance = Math.addExact(before.balance(),amount), reserved = Math.addExact(before.reserved(),reservedDelta);
        if (balance<0 || reserved<0 || reserved>balance) throw error(409,"insufficient_credits");
        db.sql("UPDATE platform_wallet_bucket SET balance=:balance,reserved=:reserved WHERE id=:id")
            .param("balance",balance).param("reserved",reserved).param("id",before.id()).update();
        db.sql("""
            INSERT INTO platform_wallet_ledger(user_id,bucket_id,event_key,kind,app_id,operation_id,amount,reserved_delta,balance_after,reserved_after,detail)
            VALUES(:user,:bucket,:key,:kind,:app,:operation,:amount,:delta,:balance,:reserved,:detail)
            """).param("user",user).param("bucket",before.id()).param("key",key).param("kind",kind)
            .param("app",app,java.sql.Types.VARCHAR).param("operation",operation,java.sql.Types.VARCHAR)
            .param("amount",amount).param("delta",reservedDelta).param("balance",balance).param("reserved",reserved).param("detail",detail).update();
        return new Bucket(before.id(),balance,reserved,before.expires());
    }
    public void expireReleased(UUID user, Bucket bucket, String event) {
        boolean pendingToken=db.sql("SELECT EXISTS(SELECT 1 FROM platform_wallet_operation WHERE bucket_id=:id AND mode='TOKEN' AND status='RESERVED')").param("id",bucket.id()).query(Boolean.class).single();
        if (!pendingToken && bucket.expires()!=null && !bucket.expires().isAfter(now()) && bucket.balance()>bucket.reserved())
            change(user,bucket,-(bucket.balance()-bucket.reserved()),0,event,"EXPIRE",null,null,"released_after_expiry");
    }
}
