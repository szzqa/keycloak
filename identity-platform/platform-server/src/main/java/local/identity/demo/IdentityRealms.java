package local.identity.demo;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** The only trusted identity domains of this product. Never discover an issuer supplied by a token. */
@Component
public class IdentityRealms {
    private final Map<String, String> issuers;
    public IdentityRealms(@Value("${identity.consumer-issuer}") String consumer) {
        issuers = Map.of("consumer", consumer);
    }
    public Map<String, String> issuers() { return issuers; }
    public String realm(String issuer) {
        return issuers.entrySet().stream().filter(entry -> entry.getValue().equals(issuer)).map(Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error("invalid_issuer")));
    }
}
