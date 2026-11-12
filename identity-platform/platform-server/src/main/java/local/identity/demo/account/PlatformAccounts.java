package local.identity.demo.account;

import static local.identity.demo.wallet.WalletTypes.*;
import java.util.*;
import local.identity.demo.UserCenter;
import local.identity.demo.TokenStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformAccounts {
    public record App(String id,String name,String url,String clientId,String serviceClient,String audience,boolean consumerAllowed,boolean migrationReady) { }
    public record User(UUID id,String displayName,Policy policy,boolean employeeEligible) { }
    private final JdbcClient db;
    private final UserCenter users;
    private final TokenStatus status;
    public PlatformAccounts(JdbcClient db,UserCenter users,TokenStatus status) { this.db=db;this.users=users;this.status=status; }
    public User user(Jwt jwt) {
        var current=status.account(jwt.getTokenValue());
        var profile=users.profile(jwt);
        UUID id=UUID.fromString((String)profile.get("userId"));
        var policy=db.sql("SELECT policy FROM platform_wallet WHERE user_id=:id AND enabled").param("id",id)
            .query(String.class).optional().orElseThrow(()->error(403,"account_disabled"));
        boolean eligible=Boolean.TRUE.equals(current.get("employeeEligible"));
        if(policy.equals("EMPLOYEE")) {
            db.sql("UPDATE platform_membership SET upstream_active=:enabled,updated_at=now() WHERE user_id=:id")
                .param("enabled",eligible).param("id",id).update();
            boolean locallyEnabled=db.sql("SELECT EXISTS(SELECT 1 FROM platform_membership WHERE user_id=:id AND enabled)").param("id",id).query(Boolean.class).single();
            if(!eligible || !locallyEnabled) throw error(403,"employee_inactive");
        }
        return new User(id,(String)profile.get("displayName"),Policy.valueOf(policy),eligible);
    }
    public App application(String serviceClient) {
        return registeredApplication(serviceClient,true);
    }
    public App registeredApplication(String serviceClient,boolean requireEnabled) {
        return db.sql("SELECT * FROM platform_application WHERE service_client_id=:client AND (enabled OR NOT :required)")
            .param("required",requireEnabled)
            .param("client",serviceClient).query((r,i)->new App(r.getString("id"),r.getString("name"),r.getString("launch_url"),
                r.getString("client_id"),r.getString("service_client_id"),r.getString("audience"),r.getBoolean("consumer_allowed"),r.getBoolean("migration_ready")))
            .optional().orElseThrow(()->error(403,"application_not_allowed"));
    }
    public List<Map<String,Object>> applications(User user) {
        return db.sql("SELECT id,name,launch_url FROM platform_application WHERE enabled AND (consumer_allowed OR :employee) ORDER BY id")
            .param("employee",user.policy()==Policy.EMPLOYEE && user.employeeEligible())
            .query((r,i)->Map.<String,Object>of("id",r.getString("id"),"name",r.getString("name"),"url",r.getString("launch_url"))).list();
    }
    public void requireAccess(App app,User user) {
        if(!app.consumerAllowed() && !(user.policy()==Policy.EMPLOYEE && user.employeeEligible())) throw error(403,"application_access_denied");
    }
    public UUID delegatedUser(App app,String localUserId) {
        bounded(localUserId,191);
        if(!db.sql("SELECT delegated_billing FROM platform_application WHERE id=:id").param("id",app.id()).query(Boolean.class).single())throw error(403,"delegated_billing_disabled");
        return mappedUser(app,localUserId);
    }
    public UUID mappedUser(App app,String localUserId) {
        bounded(localUserId,191);
        return db.sql("SELECT user_id FROM platform_app_account WHERE app_id=:app AND local_user_id=:local")
            .param("app",app.id()).param("local",localUserId).query(UUID.class).optional().orElseThrow(()->error(409,"migration_mapping_required"));
    }
    public void requireDelegatedAccess(App app,UUID user) {
        boolean employee=db.sql("SELECT EXISTS(SELECT 1 FROM platform_wallet w JOIN platform_membership m ON m.user_id=w.user_id WHERE w.user_id=:id AND w.enabled AND w.policy='EMPLOYEE' AND m.enabled AND m.upstream_active)")
            .param("id",user).query(Boolean.class).single();
        if(!app.consumerAllowed() && !employee)throw error(403,"application_access_denied");
    }
    @Transactional
    public Map<String,Object> resolve(App app,User user) {
        requireAccess(app,user);
        var local=db.sql("SELECT local_user_id FROM platform_app_account WHERE app_id=:app AND user_id=:user")
            .param("app",app.id()).param("user",user.id()).query(String.class).optional();
        boolean provision=false;
        if(local.isEmpty()) {
            if(!app.migrationReady()) throw error(409,"migration_mapping_required");
            String id="platform_"+user.id();
            db.sql("INSERT INTO platform_app_account(app_id,user_id,local_user_id) VALUES(:app,:user,:local) ON CONFLICT(app_id,user_id) DO NOTHING")
                .param("app",app.id()).param("user",user.id()).param("local",id).update();
            local=db.sql("SELECT local_user_id FROM platform_app_account WHERE app_id=:app AND user_id=:user")
                .param("app",app.id()).param("user",user.id()).query(String.class).optional();
        }
        String id=local.orElseThrow();
        provision=id.equals("platform_"+user.id());
        return Map.of("platformUserId",user.id(),"localUserId",id,"policy",user.policy(),"provisionAllowed",provision,"displayName",user.displayName());
    }
}
