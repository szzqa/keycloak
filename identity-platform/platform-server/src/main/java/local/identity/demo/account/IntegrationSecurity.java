package local.identity.demo.account;

import static local.identity.demo.wallet.WalletTypes.*;
import java.util.List;
import local.identity.demo.IdentityRealms;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class IntegrationSecurity {
    @Bean JwtDecoder platformUserDecoder(IdentityRealms realms) {
        String issuer=realms.issuers().get("consumer");
        var decoder=NimbusJwtDecoder.withIssuerLocation(issuer).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer)); return decoder;
    }
    @Bean @Order(0) SecurityFilterChain integration(HttpSecurity http,IdentityRealms realms) throws Exception {
        String issuer=realms.issuers().get("consumer");
        var decoder=NimbusJwtDecoder.withIssuerLocation(issuer).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>("aud",aud->aud!=null && aud.contains("identity-platform-internal")),
            new JwtClaimValidator<String>("azp",value->value!=null && !value.isBlank())));
        http.securityMatcher("/internal/v1/**").csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/internal/v1/admin/**").hasAuthority("SCOPE_platform.manage")
                .anyRequest().hasAuthority("SCOPE_platform.integrate"))
            .oauth2ResourceServer(o->o.jwt(j->j.decoder(decoder)));
        return http.build();
    }
    public static Jwt userToken(JwtDecoder decoder,String value,PlatformAccounts.App app) {
        if(value==null || value.isBlank()) throw error(401,"user_token_required");
        try {
            var jwt=decoder.decode(value);
            if(!jwt.getAudience().contains(app.audience()) || !app.clientId().equals(jwt.getClaimAsString("azp")) || jwt.getSubject()==null)
                throw error(401,"user_token_audience_invalid");
            return jwt;
        } catch(JwtException error) { throw error(401,"invalid_user_token"); }
    }
}
