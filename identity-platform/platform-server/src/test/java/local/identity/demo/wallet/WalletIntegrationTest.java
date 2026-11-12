package local.identity.demo.wallet;

import static local.identity.demo.wallet.WalletTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="PLATFORM_TEST_DB_PASSWORD",matches=".+")
class WalletIntegrationTest {
    static JdbcClient db; static DataSource source; static String schema;
    WalletStore store; WalletService wallets; CreditRequests requests; TransactionTemplate transaction; MutableClock clock;
    static class MutableClock extends Clock {
        Instant time=Instant.parse("2026-11-12T03:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return time;}
    }
    @BeforeAll static void initialize() {
        String url=System.getenv().getOrDefault("PLATFORM_TEST_DB_URL","jdbc:postgresql://127.0.0.1:55432/identity");
        if(!url.startsWith("jdbc:postgresql://127.0.0.1:")) throw new IllegalStateException("Tests require isolated loopback PostgreSQL");
        var admin=new DriverManagerDataSource(url,"identity",System.getenv("PLATFORM_TEST_DB_PASSWORD"));
        schema="platform_test_"+UUID.randomUUID().toString().replace("-","");
        JdbcClient.create(admin).sql("CREATE SCHEMA "+schema).update();
        source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"identity",System.getenv("PLATFORM_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate(); db=JdbcClient.create(source);
        db.sql("INSERT INTO platform_application(id,name,launch_url,client_id,service_client_id,audience) VALUES('tapcanvas','TapCanvas','http://127.0.0.1:5173','tapcanvas-web','tapcanvas-service','tapcanvas-api')").update();
    }
    // Retain the uniquely named test schema as evidence; never truncate the user's existing schemas.
    @BeforeEach void setup() {
        clock=new MutableClock();store=new WalletStore(db,clock);wallets=new WalletService(store);requests=new CreditRequests(store);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    UUID user(Policy policy,long credits) {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO platform_user(id,issuer,subject,display_name) VALUES(:id,'test',:subject,'fixture')").param("id",id).param("subject",id.toString()).update();
        db.sql("INSERT INTO platform_wallet(user_id,policy,daily_credits,initial_credits,request_enabled,max_request,max_daily_request,immediate_count,delayed_count,delay_minutes) VALUES(:id,:policy,:credits,:credits,true,50,100,1,1,1)")
            .param("id",id).param("policy",policy.name()).param("credits",credits).update();
        if(policy==Policy.EMPLOYEE) db.sql("INSERT INTO platform_membership(user_id,org_id,provider,external_subject,enabled) VALUES(:id,'corp','wecom',:subject,true)")
            .param("id",id).param("subject",id.toString()).update();
        return id;
    }
    Reserve input(String id,long amount,Mode mode) {return new Reserve(id,"a".repeat(64),"legacy",mode,Long.toString(amount),"project:fixture");}
    @Test void repeatedOperationsKeepOneChargeAndRejectChangedFacts() {
        UUID user=user(Policy.CONSUMER,100);String op=UUID.randomUUID().toString();
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(op,60,Mode.FIXED)));
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(op,60,Mode.FIXED)));
        assertThrows(Exception.class,()->transaction.execute(s->wallets.reserve("tapcanvas",user,input(op,61,Mode.FIXED))));
        transaction.execute(s->wallets.settle("tapcanvas",op,new Settlement("40","vendor:success")));
        transaction.execute(s->wallets.settle("tapcanvas",op,new Settlement("40","vendor:success")));
        assertEquals("60",transaction.execute(s->wallets.balance(user)).get("available"));
        assertThrows(Exception.class,()->transaction.execute(s->wallets.release("tapcanvas",op,new Release("failure"))));
    }
    @Test void concurrentReservationsCannotOverspend() throws Exception {
        UUID user=user(Policy.CONSUMER,100);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<12;i++) futures.add(pool.submit(()->{
                try {transaction.execute(s->wallets.reserve("tapcanvas",user,input(UUID.randomUUID().toString(),30,Mode.FIXED)));return true;}
                catch(Exception failure){return false;}
            }));
            long successful=0;for(var f:futures) if(f.get()) successful++;
            assertEquals(3,successful);assertEquals("10",transaction.execute(s->wallets.balance(user)).get("available"));
        }
    }
    @Test void lateSettlementUsesOriginalPeriodAndDoesNotSpendNewGrant() {
        UUID user=user(Policy.EMPLOYEE,100);String op=UUID.randomUUID().toString();
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(op,60,Mode.FIXED)));
        clock.time=clock.time.plusSeconds(86400);
        assertEquals("100",transaction.execute(s->wallets.balance(user)).get("available"));
        transaction.execute(s->wallets.settle("tapcanvas",op,new Settlement("40","vendor:success")));
        assertEquals("100",transaction.execute(s->wallets.balance(user)).get("available"));
        assertEquals("0",transaction.execute(s->wallets.balance(user)).get("reserved"));
    }
    @Test void consumerNeverRefreshesAndCannotApply() {
        UUID user=user(Policy.CONSUMER,22);transaction.execute(s->wallets.balance(user));clock.time=clock.time.plusSeconds(86400*4);
        assertEquals("22",transaction.execute(s->wallets.balance(user)).get("available"));
        assertThrows(Exception.class,()->transaction.execute(s->requests.apply(user,new CreditRequest(UUID.randomUUID(),"1","more"))));
    }
    @Test void requestRetryAndDelayedApprovalGrantOnce() {
        UUID user=user(Policy.EMPLOYEE,10);UUID first=UUID.randomUUID();
        transaction.execute(s->requests.apply(user,new CreditRequest(first,"20","generation")));
        transaction.execute(s->requests.apply(user,new CreditRequest(first,"20","generation")));
        assertEquals("30",transaction.execute(s->wallets.balance(user)).get("available"));
        UUID second=UUID.randomUUID();transaction.execute(s->requests.apply(user,new CreditRequest(second,"30","generation")));
        clock.time=clock.time.plusSeconds(61);assertTrue(requests.due().contains(second));
        transaction.executeWithoutResult(s->requests.decide(second,true,"system","scheduled_approval"));
        transaction.executeWithoutResult(s->requests.decide(second,true,"system","scheduled_approval"));
        assertEquals("60",transaction.execute(s->wallets.balance(user)).get("available"));
    }
    @Test void postpaidRecordsUncollectedAndProtectsOtherReservations() {
        UUID user=user(Policy.CONSUMER,100);String fixed=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(fixed,60,Mode.FIXED)));
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(token,0,Mode.TOKEN)));
        var settled=transaction.execute(s->wallets.settle("tapcanvas",token,new Settlement("80","usage:80")));
        assertEquals("40",settled.charged());assertEquals("40",settled.uncollected());
        transaction.execute(s->wallets.settle("tapcanvas",fixed,new Settlement("60","vendor:success")));
        assertEquals("0",transaction.execute(s->wallets.balance(user)).get("available"));
    }
    @Test void disabledEmployeeCannotStartButAuthorizedTaskCanSettle() {
        UUID user=user(Policy.EMPLOYEE,100);String op=UUID.randomUUID().toString();
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(op,20,Mode.FIXED)));
        db.sql("UPDATE platform_membership SET enabled=false WHERE user_id=:id").param("id",user).update();
        assertThrows(Exception.class,()->transaction.execute(s->wallets.reserve("tapcanvas",user,input("new",10,Mode.FIXED))));
        assertEquals("SETTLED",transaction.execute(s->wallets.settle("tapcanvas",op,new Settlement("20","success"))).status());
    }
    @Test void lateTokenChargeRetainsOriginalPostpaidRules() {
        UUID user=user(Policy.EMPLOYEE,100);String op=UUID.randomUUID().toString();
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(op,0,Mode.TOKEN)));
        clock.time=clock.time.plusSeconds(86400);assertEquals("100",transaction.execute(s->wallets.balance(user)).get("available"));
        var result=transaction.execute(s->wallets.settle("tapcanvas",op,new Settlement("80","usage")));
        assertEquals("80",result.charged());assertEquals("0",result.uncollected());
        assertEquals("100",transaction.execute(s->wallets.balance(user)).get("available"));
        assertEquals(0L,db.sql("SELECT balance FROM platform_wallet_bucket WHERE id=:id").param("id",result.bucketId()).query(Long.class).single());
    }
    @Test void manualRequestsDoNotConsumeAutomaticAmountLimit() {
        UUID user=user(Policy.EMPLOYEE,10);
        transaction.execute(s->requests.apply(user,new CreditRequest(UUID.randomUUID(),"1000","manual")));
        UUID second=UUID.randomUUID();transaction.execute(s->requests.apply(user,new CreditRequest(second,"30","automatic")));
        clock.time=clock.time.plusSeconds(61);assertTrue(requests.due().contains(second));
    }
    @Test void refundIsBoundedIdempotentAndDoesNotRenewExpiredCredits() {
        UUID user=user(Policy.EMPLOYEE,100);String op=UUID.randomUUID().toString();
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(op,50,Mode.FIXED)));
        transaction.execute(s->wallets.settle("tapcanvas",op,new Settlement("50","success")));
        var refund=new WalletService.Refund(UUID.randomUUID(),"30","approved correction");
        transaction.executeWithoutResult(s->wallets.refund("tapcanvas",op,refund,"admin"));
        transaction.executeWithoutResult(s->wallets.refund("tapcanvas",op,refund,"admin"));
        assertEquals("80",transaction.execute(s->wallets.balance(user)).get("available"));
        assertThrows(Exception.class,()->transaction.executeWithoutResult(s->wallets.refund("tapcanvas",op,new WalletService.Refund(UUID.randomUUID(),"21","overcharge"),"admin")));
        clock.time=clock.time.plusSeconds(86400);transaction.execute(s->wallets.balance(user));
        transaction.executeWithoutResult(s->wallets.refund("tapcanvas",op,new WalletService.Refund(UUID.randomUUID(),"20","late refund"),"admin"));
        assertEquals("100",transaction.execute(s->wallets.balance(user)).get("available"));
    }
    @Test void scheduledRefreshFindsOnlyMissingEmployeePeriod() {
        UUID staff=user(Policy.EMPLOYEE,100),consumer=user(Policy.CONSUMER,20);
        assertTrue(wallets.refreshDue().contains(staff));assertFalse(wallets.refreshDue().contains(consumer));
        transaction.execute(s->wallets.balance(staff));assertFalse(wallets.refreshDue().contains(staff));
        clock.time=clock.time.plusSeconds(86400);assertTrue(wallets.refreshDue().contains(staff));
    }
    @Test void unsubmittedCancellationFencesLateReserveAndReleasesLostResponse() {
        UUID user=user(Policy.CONSUMER,100);String before=UUID.randomUUID().toString(),after=UUID.randomUUID().toString();
        var cancelled=new WalletService.Cancellation(input(before,40,Mode.FIXED),"not-submitted");
        transaction.executeWithoutResult(s->wallets.cancelUnsubmitted("tapcanvas",user,cancelled));
        transaction.executeWithoutResult(s->wallets.cancelUnsubmitted("tapcanvas",user,cancelled));
        assertThrows(Exception.class,()->transaction.execute(s->wallets.reserve("tapcanvas",user,input(before,40,Mode.FIXED))));
        transaction.execute(s->wallets.reserve("tapcanvas",user,input(after,40,Mode.FIXED)));
        transaction.executeWithoutResult(s->wallets.cancelUnsubmitted("tapcanvas",user,new WalletService.Cancellation(input(after,40,Mode.FIXED),"not-submitted")));
        assertEquals("RELEASED",wallets.query("tapcanvas",after).status());
        assertEquals("100",transaction.execute(s->wallets.balance(user)).get("available"));
    }
    @Test void importedAccountWaitsForReviewedCreditOpeningAndReplayDoesNotRefill() {
        UUID user=user(Policy.CONSUMER,25);
        db.sql("UPDATE platform_wallet SET credit_active=false WHERE user_id=:id").param("id",user).update();
        assertEquals(false,transaction.execute(s->wallets.balance(user)).get("active"));
        assertThrows(Exception.class,()->transaction.execute(s->wallets.reserve("tapcanvas",user,input("before-open",1,Mode.FIXED))));
        var opening=new WalletService.Opening(UUID.randomUUID(),"reviewed-cutover","a".repeat(64),"old balances excluded");
        transaction.executeWithoutResult(s->wallets.open(user,"operator",opening));
        transaction.execute(s->wallets.reserve("tapcanvas",user,input("after-open-"+user,10,Mode.FIXED)));
        transaction.executeWithoutResult(s->wallets.open(user,"operator",opening));
        assertEquals("15",transaction.execute(s->wallets.balance(user)).get("available"));
        assertEquals(1L,db.sql("SELECT count(*) FROM platform_wallet_ledger WHERE user_id=:id AND kind='INITIAL_GRANT'").param("id",user).query(Long.class).single());
    }
    @Test void scheduledMembershipExpiryChangesFutureRulesWithoutResettingExistingBalance() {
        UUID user=user(Policy.EMPLOYEE,100);transaction.execute(s->wallets.balance(user));
        db.sql("INSERT INTO platform_wallet_rule_schedule(request_id,user_id,epoch,effective_at,daily_credits,request_enabled,max_request,max_daily_request,immediate_count,delayed_count,delay_minutes,facts) SELECT :request,user_id,epoch,:at,25,true,5,10,0,0,0,'reviewed' FROM platform_wallet WHERE user_id=:user")
            .param("request",UUID.randomUUID()).param("user",user).param("at",java.sql.Timestamp.from(clock.time.plusSeconds(60))).update();
        assertEquals(100,transaction.execute(s->store.lock(user)).daily());
        clock.time=clock.time.plusSeconds(61);assertEquals(25,transaction.execute(s->store.lock(user)).daily());
        assertEquals("100",transaction.execute(s->wallets.balance(user)).get("available"));
        clock.time=clock.time.plusSeconds(86400);assertEquals("25",transaction.execute(s->wallets.balance(user)).get("available"));
    }
}
