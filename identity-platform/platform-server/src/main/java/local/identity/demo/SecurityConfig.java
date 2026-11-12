package local.identity.demo;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.client.oidc.web.logout.*;
import org.springframework.security.oauth2.client.oidc.session.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.http.HttpStatus;

@Configuration
public class SecurityConfig {
    @Value("${server.servlet.session.cookie.name}") String cookie;
    @Value("${identity.api-audience}") String apiAudience;

    @Bean OAuth2AuthorizedClientRepository clients() { return new HttpSessionOAuth2AuthorizedClientRepository(); }
    @Bean OAuth2AuthorizedClientManager clientManager(ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository clients) {
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build());
        return manager;
    }
    @Bean HttpSessionEventPublisher sessions() { return new HttpSessionEventPublisher(); }
    @Bean OidcSessionRegistry oidcSessions() { return new InMemoryOidcSessionRegistry(); }
    @Bean OidcBackChannelLogoutHandler backChannel(OidcSessionRegistry registry) {
        var handler = new OidcBackChannelLogoutHandler(registry);
        handler.setSessionCookieName(cookie);
        return handler;
    }

    @Bean @Order(1)
    SecurityFilterChain api(HttpSecurity http, IdentityRealms realms) throws Exception {
        String issuer = realms.issuers().get("consumer");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        var audience = new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(apiAudience));
        var subject = new JwtClaimValidator<String>("sub", value -> value != null && !value.isBlank());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience, subject));
        http.securityMatcher("/api/**", "/v1/**").sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
        return http.build();
    }

    @Bean @Order(2)
    SecurityFilterChain web(HttpSecurity http, ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository clients) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        OAuth2AuthorizationRequestResolver requests = new OAuth2AuthorizationRequestResolver() {
            private org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest customize(
                    jakarta.servlet.http.HttpServletRequest request, org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest value) {
                if (value == null) return null;
                var builder = org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.from(value);
                builder.additionalParameters(p -> {
                    p.put("ui_locales", "zh-CN");
                    if ("company".equals(request.getParameter("method"))) {
                        p.put("kc_idp_hint", "company");
                        // A personal SSO cookie must not bypass the requested corporate proof.
                        p.put("prompt", "login");
                    }
                    var session = request.getSession(false);
                    // Only a CSRF-protected POST in this session can request account linking.
                    if (session != null && Boolean.TRUE.equals(session.getAttribute("link-company"))) {
                        session.removeAttribute("link-company");
                        p.put("kc_action", "idp_link:company");
                    }
                });
                return builder.build();
            }
            public org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest resolve(jakarta.servlet.http.HttpServletRequest request) {
                return customize(request, resolver.resolve(request));
            }
            public org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest resolve(jakarta.servlet.http.HttpServletRequest request, String id) {
                return customize(request, resolver.resolve(request, id));
            }
        };
        var logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/");
        http.authorizeHttpRequests(a -> a.requestMatchers("/", "/app.js", "/portal.js", "/style.css", "/auth/**", "/error", "/favicon.ico").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                request -> request.getRequestURI().startsWith("/bff/")))
            .oauth2Login(o -> o.authorizedClientRepository(clients).authorizationEndpoint(e -> e.authorizationRequestResolver(requests))
                .defaultSuccessUrl("/", true))
            .oidcLogout(o -> o.backChannel(Customizer.withDefaults()))
            .logout(l -> l.logoutSuccessHandler(logout).deleteCookies(cookie))
            .headers(h -> h.referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }
}
