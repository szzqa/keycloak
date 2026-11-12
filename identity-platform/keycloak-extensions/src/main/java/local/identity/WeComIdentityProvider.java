package local.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.keycloak.broker.provider.AbstractIdentityProvider;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.*;

/** A single-company, pre-provisioned member broker. No implicit registration or email linking. */
public final class WeComIdentityProvider extends AbstractIdentityProvider<IdentityProviderModel> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<String, AppToken> TOKENS = new ConcurrentHashMap<>();
    private final String corp;
    private final String agent;
    private final String mockBase;

    public WeComIdentityProvider(KeycloakSession session, IdentityProviderModel config) {
        super(session, config);
        corp = required(config, "corpId");
        agent = required(config, "agentId");
        mockBase = config.getConfig().getOrDefault("mockBaseUrl", "");
        if (!mockBase.isEmpty() && (!Boolean.getBoolean("identity.wecom.mock")
                || !mockBase.matches("http://127\\.0\\.0\\.1:[0-9]+/wecom"))) {
            throw new IllegalArgumentException("WeCom mock requires explicit development mode and loopback URL");
        }
    }

    static String required(IdentityProviderModel config, String key) {
        String value = config.getConfig().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing WeCom setting: " + key);
        return value;
    }

    public static String externalId(String corp, String userId) {
        if (corp == null || !corp.matches("[a-zA-Z0-9_-]+") || userId == null || userId.isBlank())
            throw new IllegalArgumentException("Invalid corporate member identity");
        return corp + ":" + userId;
    }

    private String stateKey(String state) {
        return "identity.wecom.state:" + session.getContext().getRealm().getId() + ":" + getConfig().getAlias() + ":" + state;
    }

    @Override
    public Response performLogin(AuthenticationRequest request) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        var auth = request.getAuthenticationSession();
        session.singleUseObjects().put(stateKey(state), 300, Map.of(
                "brokerState", request.getState().getEncoded(),
                "root", auth.getParentSession().getId(), "tab", auth.getTabId(),
                "expires", Long.toString(System.currentTimeMillis() + 300_000)));
        String ua = request.getHttpRequest().getHttpHeaders().getHeaderString("User-Agent");
        boolean inApp = ua != null && ua.toLowerCase(java.util.Locale.ROOT).contains("wxwork");
        String endpoint = mockBase.isEmpty()
                ? (inApp ? "https://open.weixin.qq.com/connect/oauth2/authorize" : "https://login.work.weixin.qq.com/wwlogin/sso/login")
                : mockBase + (inApp ? "/connect/oauth2/authorize" : "/wwlogin/sso/login");
        UriBuilder uri = UriBuilder.fromUri(endpoint).queryParam("appid", corp)
                .queryParam("agentid", agent).queryParam("redirect_uri", request.getRedirectUri())
                .queryParam("state", state);
        if (inApp) uri.queryParam("response_type", "code").queryParam("scope", "snsapi_base").fragment("wechat_redirect");
        else uri.queryParam("login_type", "CorpApp");
        return Response.seeOther(uri.build()).build();
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new Endpoint(this, realm, callback);
    }

    @jakarta.ws.rs.ext.Provider
    @jakarta.enterprise.inject.Vetoed
    public static final class Endpoint {
        private final WeComIdentityProvider provider;
        private final KeycloakSession session;
        private final RealmModel realm;
        private final AuthenticationCallback callback;
        public Endpoint(WeComIdentityProvider provider, RealmModel realm, AuthenticationCallback callback) {
            this.provider = provider; this.session = provider.session; this.realm = realm; this.callback = callback;
        }

        @GET
        public Response get(@QueryParam("state") String state, @QueryParam("code") String code) {
            if (state == null || !state.matches("[a-f0-9]{64}")) return rejected(400);
            // remove is atomic across Keycloak nodes; retention alone is not an expiry check.
            Map<String, String> saved = session.singleUseObjects().remove(provider.stateKey(state));
            if (saved == null || Long.parseLong(saved.get("expires")) < System.currentTimeMillis()) return rejected(400);
            var auth = callback.getAndVerifyAuthenticationSession(saved.get("brokerState"));
            if (auth == null || !saved.get("root").equals(auth.getParentSession().getId())
                    || !saved.get("tab").equals(auth.getTabId())) return rejected(400);
            session.getContext().setAuthenticationSession(auth);
            if (code == null || code.isBlank() || code.length() > 1024) return rejected(400);
            try {
                JsonNode profile = json(UriBuilder.fromUri(provider.api() + "/cgi-bin/auth/getuserinfo")
                        .queryParam("access_token", provider.appToken()).queryParam("code", code).build());
                String userId = profile.path("userid").asText("");
                if (userId.isBlank()) return rejected(403);
                String id = externalId(provider.corp, userId);
                UserModel user = session.users().getUserByFederatedIdentity(realm,
                        new FederatedIdentityModel(provider.getConfig().getAlias(), id, userId));
                if (!WorkforceAdmission.allowed(realm, user)) return rejected(403);
                var identity = new BrokeredIdentityContext(id, provider.getConfig());
                identity.setIdp(provider);
                identity.setUsername(user.getUsername());
                identity.setAuthenticationSession(auth);
                return callback.authenticated(identity);
            } catch (Exception failure) {
                // Do not log request URLs, upstream bodies, codes, secrets or tokens.
                return rejected(502);
            }
        }
    }

    static Response rejected(int status) {
        return Response.status(status).type("text/plain;charset=UTF-8")
                .header("Cache-Control", "no-store")
                .entity(status == 403 ? "无访问权限，请联系公司管理员。" : "企业微信认证未完成，请返回应用重新登录。").build();
    }

    private String api() { return mockBase.isEmpty() ? "https://qyapi.weixin.qq.com" : mockBase; }

    private String appToken() throws Exception {
        String secret = mockBase.isEmpty() ? System.getenv(required(getConfig(), "secretEnv")) : "local-mock-secret";
        if (secret == null || secret.isBlank()) throw new IllegalStateException("WeCom secret unavailable");
        String key = session.getContext().getRealm().getId() + ":" + getConfig().getAlias();
        AppToken entry = TOKENS.computeIfAbsent(key, ignored -> new AppToken());
        synchronized (entry) {
            if (entry.expires > System.currentTimeMillis() && secret.equals(entry.secret) && corp.equals(entry.corp) && api().equals(entry.api)) return entry.token;
            JsonNode response = json(UriBuilder.fromUri(api() + "/cgi-bin/gettoken")
                    .queryParam("corpid", corp).queryParam("corpsecret", secret).build());
            String token = response.path("access_token").asText("");
            int ttl = response.path("expires_in").asInt(0);
            if (token.isBlank() || ttl <= 0) throw new IllegalStateException("Invalid app token response");
            entry.token = token; entry.secret = secret; entry.corp = corp; entry.api = api();
            entry.expires = System.currentTimeMillis() + Math.max(1, ttl - 60) * 1000L;
            return token;
        }
    }

    private static JsonNode json(URI uri) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 65_536) throw new IllegalStateException("Upstream HTTP failure");
        JsonNode json = JSON.readTree(response.body());
        if (json == null || !json.isObject() || json.path("errcode").asInt(0) != 0) throw new IllegalStateException("Upstream API failure");
        return json;
    }

    @Override public void updateBrokeredUser(KeycloakSession s, RealmModel r, UserModel u, BrokeredIdentityContext c) { }
    @Override public void updateBrokeredEmail(KeycloakSession s, RealmModel r, UserModel u, BrokeredIdentityContext c) { }
    @Override public Response retrieveToken(KeycloakSession s, FederatedIdentityModel i) { return Response.status(400).build(); }
    @Override public Response retrieveToken(KeycloakSession s, FederatedIdentityModel i, UserSessionModel us, UserModel u) { return Response.status(400).build(); }
    private static final class AppToken { String token, secret, corp, api; long expires; }
}
