package local.identity.demo.account;

import static local.identity.demo.wallet.WalletTypes.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Live checks are made before, never inside, wallet transactions. */
@Component
public class UpstreamEligibility {
    private final RestClient client;
    private final JdbcClient db;
    private final String issuer,clientId,secret;
    private String token;
    private long expires;
    public UpstreamEligibility(JdbcClient db,@Value("${identity.consumer-issuer}") String issuer,
        @Value("${identity.operations-client-id:identity-platform-operations}") String clientId,
        @Value("${identity.operations-client-secret:}") String secret) {
        this.db=db;this.issuer=issuer;this.clientId=clientId;this.secret=secret;
        var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));client=RestClient.builder().requestFactory(factory).build();
    }
    private synchronized String token() {
        if(token!=null && expires>System.currentTimeMillis()+30000)return token;
        if(secret.isBlank())throw error(503,"eligibility_client_not_configured");
        var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","client_credentials");form.add("client_id",clientId);form.add("client_secret",secret);
        var result=client.post().uri(issuer+"/protocol/openid-connect/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        if(result==null || !(result.get("access_token") instanceof String value) || !(result.get("expires_in") instanceof Number seconds))throw error(503,"eligibility_token_invalid");
        token=value;expires=System.currentTimeMillis()+seconds.longValue()*1000;return token;
    }
    public Map<?,?> check(UUID user) {
        var subject=db.sql("SELECT subject FROM platform_user WHERE id=:id AND enabled").param("id",user).query(String.class)
            .optional().orElseThrow(()->error(403,"account_disabled"));
        Map<?,?> result;
        try {result=client.get().uri(issuer+"/identity-platform/eligibility/{subject}",subject).headers(h->h.setBearerAuth(token())).retrieve().body(Map.class);}
        catch(Exception failure){throw error(503,"eligibility_unavailable");}
        if(result==null)throw error(503,"eligibility_unavailable");
        boolean employee=Boolean.TRUE.equals(result.get("employeeEligible"));
        db.sql("UPDATE platform_membership SET upstream_active=:active,updated_at=now() WHERE user_id=:id").param("active",employee).param("id",user).update();
        if(!Boolean.TRUE.equals(result.get("accountEnabled")))throw error(403,"account_disabled");
        String policy=db.sql("SELECT policy FROM platform_wallet WHERE user_id=:id AND enabled").param("id",user).query(String.class)
            .optional().orElseThrow(()->error(403,"account_disabled"));
        if(policy.equals("EMPLOYEE") && !employee)throw error(403,"employee_inactive");
        if(policy.equals("CONSUMER") && !Boolean.TRUE.equals(result.get("emailVerified")) && !employee)throw error(403,"email_verification_required");
        return result;
    }
}
