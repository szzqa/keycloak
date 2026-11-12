package local.identity.demo;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Business data lives here; passwords and verified identity bindings remain in Keycloak. */
@Service
@org.springframework.context.annotation.DependsOn("platformMigrations")
public class UserCenter {
    private final JdbcClient database;
    private final long initialCredits;
    public UserCenter(JdbcClient database, @org.springframework.beans.factory.annotation.Value("${identity.consumer-initial-credits:0}") long initialCredits) {
        if(initialCredits<0) throw new IllegalArgumentException("Negative initial credits");
        this.database = database; this.initialCredits=initialCredits;
    }
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> profile(Jwt token) {
        String initial = token.getClaimAsString("preferred_username");
        if (initial == null) initial = "新用户";
        var profile = database.sql("""
            INSERT INTO platform_user (id, issuer, subject, display_name) VALUES (:id, :issuer, :subject, :name)
            ON CONFLICT (issuer, subject) DO UPDATE SET last_seen_at = now()
            RETURNING id, display_name, enabled
            """).param("id", UUID.randomUUID()).param("issuer", token.getIssuer().toString())
            .param("subject", token.getSubject()).param("name", initial.substring(0, Math.min(initial.length(), 80)))
            .query((row, index) -> Map.<String,Object>of("userId", row.getString("id"), "displayName", row.getString("display_name"), "enabled",row.getBoolean("enabled")))
            .single();
        if(!Boolean.TRUE.equals(profile.get("enabled"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"account_disabled");
        database.sql("INSERT INTO platform_wallet(user_id,policy,initial_credits) VALUES(:id,'CONSUMER',:initial) ON CONFLICT(user_id) DO NOTHING")
            .param("id",UUID.fromString((String)profile.get("userId"))).param("initial",initialCredits).update();
        return profile;
    }
    public void rename(Jwt token, String name) {
        if (name == null || name.isBlank() || name.strip().length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "昵称须为 1–80 个字符");
        profile(token);
        database.sql("UPDATE platform_user SET display_name=:name WHERE issuer=:issuer AND subject=:subject")
            .param("name", name.strip()).param("issuer", token.getIssuer().toString()).param("subject", token.getSubject()).update();
    }
}
