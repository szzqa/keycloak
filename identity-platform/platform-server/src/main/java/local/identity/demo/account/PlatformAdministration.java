package local.identity.demo.account;

import static local.identity.demo.wallet.WalletTypes.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.*;
import local.identity.demo.IdentityRealms;
import local.identity.demo.wallet.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/admin")
public class PlatformAdministration {
    private final JdbcClient db; private final IdentityRealms realms; private final WalletStore store; private final CreditRequests requests;
    private final WalletService wallets;private final UpstreamEligibility eligibility;
    public PlatformAdministration(JdbcClient db,IdentityRealms realms,WalletStore store,CreditRequests requests,WalletService wallets,UpstreamEligibility eligibility) {this.db=db;this.realms=realms;this.store=store;this.requests=requests;this.wallets=wallets;this.eligibility=eligibility;}
    public record Application(String id,String name,String launchUrl,String clientId,String serviceClientId,String audience,boolean enabled,boolean consumerAllowed,boolean migrationReady,boolean delegatedBilling) { }
    public record WalletConfig(Policy policy,String dailyCredits,String initialCredits,String resetTime,boolean requestEnabled,
            String maxRequest,String maxDailyRequest,int immediateCount,int delayedCount,int delayMinutes) {
        void validate() {
            if(policy==null)throw error(400,"policy_required");credits(dailyCredits);credits(initialCredits);credits(maxRequest);credits(maxDailyRequest);
            LocalTime.parse(resetTime);
            if(immediateCount<0 || delayedCount<0 || delayMinutes<0 || delayMinutes>10080)throw error(400,"invalid_request_rules");
            if(policy==Policy.CONSUMER && (requestEnabled || !dailyCredits.equals("0")))throw error(400,"consumer_policy_invalid");
        }
    }
    public record Membership(String orgId,String provider,String subject,boolean enabled) { }
    public record ImportAccount(String batchId,String appId,String localUserId,String subject,String displayName,boolean enabled,WalletConfig wallet,Membership membership) { }
    public record Adjustment(UUID requestId,String amount,String reason) { }
    public record Decision(boolean approve,String reason) { }
    public record PolicyChange(UUID requestId,WalletConfig wallet,String reason) { }
    public record MembershipChange(UUID requestId,Membership membership,String reason) { }
    public record AccountState(UUID requestId,boolean enabled,String reason) { }
    public record RuleSchedule(UUID requestId,java.time.Instant effectiveAt,WalletConfig wallet,String reason) { }
    private static String digest(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    private void audit(String actor,String action,UUID user,String request,String facts) {
        db.sql("INSERT INTO platform_admin_audit(actor,action,user_id,request_id,facts) VALUES(:actor,:action,:user,:request,:facts)")
            .param("actor",actor).param("action",action).param("user",user,java.sql.Types.OTHER).param("request",request).param("facts",facts).update();
    }
    private boolean replay(String key,String facts) {
        var old=db.sql("SELECT facts FROM platform_admin_audit WHERE request_id=:id").param("id",key).query(String.class).optional();
        if(old.isEmpty())return false;
        if(!old.get().equals(facts))throw error(409,"admin_request_conflict");return true;
    }
    @GetMapping("/applications/{id}")
    public Application application(@PathVariable String id) {
        return db.sql("SELECT * FROM platform_application WHERE id=:id").param("id",id)
            .query((rs,row)->new Application(rs.getString("id"),rs.getString("name"),rs.getString("launch_url"),
                rs.getString("client_id"),rs.getString("service_client_id"),rs.getString("audience"),
                rs.getBoolean("enabled"),rs.getBoolean("consumer_allowed"),rs.getBoolean("migration_ready"),rs.getBoolean("delegated_billing")))
            .optional().orElseThrow(()->error(404,"application_not_found"));
    }
    @PostMapping("/applications") @Transactional
    public Object application(@AuthenticationPrincipal Jwt actor,@RequestBody Application input) {
        bounded(input.id(),80);bounded(input.name(),120);bounded(input.clientId(),191);bounded(input.serviceClientId(),191);bounded(input.audience(),191);
        URI url=URI.create(input.launchUrl());
        if(url.getUserInfo()!=null || !("https".equals(url.getScheme()) || "http".equals(url.getScheme()) && Set.of("localhost","127.0.0.1").contains(url.getHost())))throw error(400,"application_url_invalid");
        db.sql("""
            INSERT INTO platform_application(id,name,launch_url,client_id,service_client_id,audience,enabled,consumer_allowed,migration_ready,delegated_billing)
            VALUES(:id,:name,:url,:client,:service,:aud,:enabled,:consumer,:ready,:delegate)
            ON CONFLICT(id) DO UPDATE SET name=excluded.name,launch_url=excluded.launch_url,client_id=excluded.client_id,
            service_client_id=excluded.service_client_id,audience=excluded.audience,enabled=excluded.enabled,consumer_allowed=excluded.consumer_allowed,migration_ready=excluded.migration_ready,delegated_billing=excluded.delegated_billing
            """).param("id",input.id()).param("name",input.name()).param("url",input.launchUrl()).param("client",input.clientId())
            .param("service",input.serviceClientId()).param("aud",input.audience()).param("enabled",input.enabled()).param("consumer",input.consumerAllowed()).param("ready",input.migrationReady()).param("delegate",input.delegatedBilling()).update();
        audit(actor.getSubject(),"APPLICATION_CONFIG",null,UUID.randomUUID().toString(),input.toString());return input;
    }
    @PostMapping("/imports/accounts") @Transactional
    public Object importAccount(@AuthenticationPrincipal Jwt actor,@RequestBody ImportAccount input) {
        bounded(input.batchId(),191);bounded(input.appId(),80);bounded(input.localUserId(),191);bounded(input.subject(),255);bounded(input.displayName(),80);
        if(input.wallet()==null)throw error(400,"wallet_rules_required");input.wallet().validate();
        if(input.wallet().policy()==Policy.EMPLOYEE && input.membership()==null)throw error(400,"employee_identity_required");
        String checksum=digest(input.toString());
        var prior=db.sql("SELECT record_digest,user_id FROM platform_migration_record WHERE batch_id=:batch AND app_id=:app AND local_user_id=:local")
            .param("batch",input.batchId()).param("app",input.appId()).param("local",input.localUserId()).query().listOfRows();
        if(!prior.isEmpty()) {
            if(!checksum.equals(prior.getFirst().get("record_digest")))throw error(409,"migration_record_conflict");
            return Map.of("userId",prior.getFirst().get("user_id"),"status","COMPLETE");
        }
        String issuer=realms.issuers().get("consumer");
        UUID user=db.sql("SELECT id FROM platform_user WHERE issuer=:issuer AND subject=:subject")
            .param("issuer",issuer).param("subject",input.subject()).query(UUID.class).optional().orElse(UUID.randomUUID());
        db.sql("INSERT INTO platform_user(id,issuer,subject,display_name,enabled) VALUES(:id,:issuer,:subject,:name,:enabled) ON CONFLICT(id) DO NOTHING")
            .param("id",user).param("issuer",issuer).param("subject",input.subject()).param("name",input.displayName()).param("enabled",input.enabled()).update();
        var already=db.sql("SELECT local_user_id FROM platform_app_account WHERE app_id=:app AND user_id=:user")
            .param("app",input.appId()).param("user",user).query(String.class).optional();
        if(already.isPresent() && !already.get().equals(input.localUserId()))throw error(409,"legacy_account_conflict");
        boolean hasWallet=db.sql("SELECT EXISTS(SELECT 1 FROM platform_wallet WHERE user_id=:user)").param("user",user).query(Boolean.class).single();
        if(!hasWallet)db.sql("INSERT INTO platform_wallet(user_id,policy,credit_active) VALUES(:user,:policy,false)").param("user",user).param("policy",input.wallet().policy().name()).update();
        Rules rules=store.lock(user);
        boolean history=db.sql("SELECT EXISTS(SELECT 1 FROM platform_wallet_bucket WHERE user_id=:user)").param("user",user).query(Boolean.class).single();
        if(history)throw error(409,"existing_wallet_requires_review");
        db.sql("UPDATE platform_wallet SET credit_active=false WHERE user_id=:user").param("user",user).update();
        configure(user,input.wallet());
        db.sql("INSERT INTO platform_app_account(app_id,user_id,local_user_id,migration_batch) VALUES(:app,:user,:local,:batch) ON CONFLICT(app_id,user_id) DO NOTHING")
            .param("app",input.appId()).param("user",user).param("local",input.localUserId()).param("batch",input.batchId()).update();
        if(input.membership()!=null) {
            var member=input.membership();bounded(member.orgId(),191);bounded(member.provider(),80);bounded(member.subject(),255);
            db.sql("INSERT INTO platform_membership(user_id,org_id,provider,external_subject,enabled) VALUES(:user,:org,:provider,:subject,:enabled)")
                .param("user",user).param("org",member.orgId()).param("provider",member.provider()).param("subject",member.subject()).param("enabled",member.enabled()).update();
        }
        db.sql("INSERT INTO platform_migration_record(batch_id,app_id,local_user_id,record_digest,user_id,status) VALUES(:batch,:app,:local,:digest,:user,'COMPLETE')")
            .param("batch",input.batchId()).param("app",input.appId()).param("local",input.localUserId()).param("digest",checksum).param("user",user).update();
        audit(actor.getSubject(),"IMPORT_ACCOUNT",user,"import:"+digest(input.batchId()+":"+input.appId()+":"+input.localUserId()),checksum);
        return Map.of("userId",user,"status","COMPLETE");
    }
    private void configure(UUID user,WalletConfig input) {
        input.validate();
        db.sql("UPDATE platform_wallet_rule_schedule SET active=false WHERE user_id=:user AND active").param("user",user).update();
        db.sql("""
            UPDATE platform_wallet SET policy=:policy,daily_credits=:daily,initial_credits=:initial,reset_time=:reset,
            request_enabled=:requests,max_request=:max,max_daily_request=:day,immediate_count=:immediate,delayed_count=:delayed,delay_minutes=:minutes WHERE user_id=:user
            """).param("policy",input.policy().name()).param("daily",credits(input.dailyCredits())).param("initial",credits(input.initialCredits()))
            .param("reset",LocalTime.parse(input.resetTime())).param("requests",input.requestEnabled()).param("max",credits(input.maxRequest()))
            .param("day",credits(input.maxDailyRequest())).param("immediate",input.immediateCount()).param("delayed",input.delayedCount()).param("minutes",input.delayMinutes()).param("user",user).update();
    }
    @PostMapping("/wallets/{user}/adjust") @Transactional
    public Object adjust(@AuthenticationPrincipal Jwt actor,@PathVariable UUID user,@RequestBody Adjustment input) {
        if(input.requestId()==null)throw error(400,"request_id_required");bounded(input.reason(),1000);
        if(input.amount()==null || !input.amount().matches("-?(0|[1-9][0-9]{0,17})"))throw error(400,"invalid_credit_amount");
        Rules rules=store.lock(user);String facts=user+":"+input;
        if(replay(input.requestId().toString(),facts))return Map.of("ok",true);
        store.requireActive(rules);Bucket bucket=store.current(rules);
        store.change(user,bucket,Long.parseLong(input.amount()),0,"adjust:"+input.requestId(),"ADJUST",null,null,input.reason());
        audit(actor.getSubject(),"WALLET_ADJUST",user,input.requestId().toString(),facts);return Map.of("ok",true);
    }
    @PostMapping("/wallets/{user}/policy") @Transactional
    public Object policy(@AuthenticationPrincipal Jwt actor,@PathVariable UUID user,@RequestBody PolicyChange input) {
        if(input.requestId()==null || input.wallet()==null)throw error(400,"policy_change_invalid");input.wallet().validate();bounded(input.reason(),1000);
        Rules rules=store.lock(user);String facts=user+":"+input;
        if(replay(input.requestId().toString(),facts))return Map.of("ok",true);
        boolean pending=db.sql("SELECT EXISTS(SELECT 1 FROM platform_wallet_operation WHERE user_id=:user AND status='RESERVED') OR EXISTS(SELECT 1 FROM platform_credit_request WHERE user_id=:user AND status='PROCESSING')")
            .param("user",user).query(Boolean.class).single();
        if(pending)throw error(409,"pending_operations_require_reconciliation");
        if(rules.policy()!=input.wallet().policy()) {
            if(input.wallet().policy()==Policy.EMPLOYEE && !db.sql("SELECT EXISTS(SELECT 1 FROM platform_membership WHERE user_id=:id AND enabled AND upstream_active)").param("id",user).query(Boolean.class).single())throw error(409,"employee_membership_required");
            var buckets=db.sql("SELECT id FROM platform_wallet_bucket WHERE user_id=:id AND epoch=:epoch AND balance>0").param("id",user).param("epoch",rules.epoch()).query(UUID.class).list();
            for(UUID id:buckets) {var bucket=store.bucket(id);store.change(user,bucket,-bucket.balance(),0,"policy-archive:"+input.requestId()+":"+id,"ARCHIVE",null,null,input.reason());}
            db.sql("UPDATE platform_wallet SET epoch=epoch+1 WHERE user_id=:user").param("user",user).update();
        }
        configure(user,input.wallet());audit(actor.getSubject(),"WALLET_POLICY",user,input.requestId().toString(),facts);return Map.of("ok",true);
    }
    @PostMapping("/credit-requests/{id}/decision") public Object decide(@AuthenticationPrincipal Jwt actor,@PathVariable UUID id,@RequestBody Decision input) {
        if(input.approve())eligibility.check(requests.owner(id));
        requests.decide(id,input.approve(),actor.getSubject(),input.reason());return Map.of("ok",true);
    }
    @PostMapping("/applications/{app}/operations/{operation}/refund") public Object refund(@AuthenticationPrincipal Jwt actor,@PathVariable String app,@PathVariable String operation,@RequestBody WalletService.Refund input) {
        wallets.refund(app,operation,input,actor.getSubject());return Map.of("ok",true);
    }
    @PostMapping("/users/{user}/membership") @Transactional public Object membership(@AuthenticationPrincipal Jwt actor,@PathVariable UUID user,@RequestBody MembershipChange input) {
        if(input.requestId()==null || input.membership()==null)throw error(400,"membership_required");bounded(input.reason(),1000);
        var m=input.membership();bounded(m.orgId(),191);bounded(m.provider(),80);bounded(m.subject(),255);
        store.lock(user);String facts=user+":"+input;if(replay(input.requestId().toString(),facts))return Map.of("ok",true);
        db.sql("INSERT INTO platform_membership(user_id,org_id,provider,external_subject,enabled,upstream_active) VALUES(:id,:org,:provider,:subject,:enabled,false) ON CONFLICT(user_id) DO UPDATE SET org_id=excluded.org_id,provider=excluded.provider,external_subject=excluded.external_subject,enabled=excluded.enabled,upstream_active=false,updated_at=now()")
            .param("id",user).param("org",m.orgId()).param("provider",m.provider()).param("subject",m.subject()).param("enabled",m.enabled()).update();
        audit(actor.getSubject(),"MEMBERSHIP",user,input.requestId().toString(),facts);return Map.of("ok",true);
    }
    @PostMapping("/users/{user}/state") @Transactional public Object state(@AuthenticationPrincipal Jwt actor,@PathVariable UUID user,@RequestBody AccountState input) {
        if(input.requestId()==null)throw error(400,"request_id_required");bounded(input.reason(),1000);store.lock(user);
        String facts=user+":"+input;if(replay(input.requestId().toString(),facts))return Map.of("ok",true);
        db.sql("UPDATE platform_user SET enabled=:enabled WHERE id=:id").param("enabled",input.enabled()).param("id",user).update();
        audit(actor.getSubject(),"ACCOUNT_STATE",user,input.requestId().toString(),facts);return Map.of("ok",true);
    }
    @PostMapping("/users/{user}/eligibility/refresh") public Object refresh(@PathVariable UUID user) {return eligibility.check(user);}
    @PostMapping("/wallets/{user}/rule-schedules") @Transactional
    public Object schedule(@AuthenticationPrincipal Jwt actor,@PathVariable UUID user,@RequestBody RuleSchedule input) {
        if(input.requestId()==null || input.effectiveAt()==null || input.wallet()==null)throw error(400,"rule_schedule_required");
        bounded(input.reason(),1000);input.wallet().validate();Rules current=store.lock(user);
        String facts=user+":"+input;
        if(replay(input.requestId().toString(),facts))return Map.of("ok",true);
        var rules=input.wallet();
        if(current.policy()!=Policy.EMPLOYEE || rules.policy()!=Policy.EMPLOYEE || !rules.initialCredits().equals("0") || !LocalTime.parse(rules.resetTime()).equals(current.resetTime()))
            throw error(400,"scheduled_policy_or_period_change_forbidden");
        db.sql("""
            INSERT INTO platform_wallet_rule_schedule(request_id,user_id,epoch,effective_at,daily_credits,request_enabled,max_request,max_daily_request,immediate_count,delayed_count,delay_minutes,facts)
            VALUES(:id,:user,:epoch,:at,:daily,:enabled,:maximum,:day,:immediate,:delayed,:delay,:facts)
            """).param("id",input.requestId()).param("user",user).param("epoch",current.epoch()).param("at",java.sql.Timestamp.from(input.effectiveAt()))
            .param("daily",credits(rules.dailyCredits())).param("enabled",rules.requestEnabled()).param("maximum",credits(rules.maxRequest()))
            .param("day",credits(rules.maxDailyRequest())).param("immediate",rules.immediateCount()).param("delayed",rules.delayedCount()).param("delay",rules.delayMinutes()).param("facts",facts).update();
        audit(actor.getSubject(),"SCHEDULE_RULES",user,input.requestId().toString(),facts);return Map.of("ok",true);
    }
    @PostMapping("/wallets/{user}/open")
    public Object open(@AuthenticationPrincipal Jwt actor,@PathVariable UUID user,@RequestBody WalletService.Opening input) {
        eligibility.check(user);
        wallets.open(user,actor.getSubject(),input);return Map.of("ok",true);
    }
}
