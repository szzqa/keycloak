package local.identity.demo;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class WebController {
    private final OAuth2AuthorizedClientManager clients;
    private final TokenStatus status;
    private final RestClient api;
    private final IdentityRealms realms;
    private final UserCenter users;
    @Value("${server.port}") int port;
    @Value("${identity.product-id}") String productId;
    @Value("${identity.product-name}") String productName;
    @Value("${identity.other-product}") String otherProduct;
    public WebController(OAuth2AuthorizedClientManager clients, TokenStatus status, IdentityRealms realms, UserCenter users) {
        this.clients = clients; this.status = status; this.realms = realms; this.users = users;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        api = RestClient.builder().requestFactory(factory).build();
    }
    @GetMapping("/") String home(Model model, Authentication authentication) {
        model.addAttribute("accountUrl", realms.issuers().get("consumer") + "/account/");
        model.addAttribute("signedIn", authentication instanceof OAuth2AuthenticationToken);
        model.addAttribute("productName", productName);
        model.addAttribute("otherProduct", otherProduct);
        return "index";
    }
    @GetMapping("/auth/login") String login() { return "redirect:/oauth2/authorization/consumer"; }
    @GetMapping("/auth/login/wecom") String wecom() { return "redirect:/oauth2/authorization/consumer?method=company"; }
    @PostMapping("/account/link/company") String link(HttpServletRequest request) {
        request.getSession().setAttribute("link-company", true);
        return "redirect:/oauth2/authorization/consumer";
    }
    @GetMapping({"/bff/me", "/bff/employee/me"}) @ResponseBody
    ResponseEntity<?> me(Authentication principal, HttpServletRequest request, HttpServletResponse response) {
        return proxy(principal, request, response, request.getRequestURI().replace("/bff/", "/api/"), null);
    }
    public record ProfileUpdate(String displayName) { }
    @PostMapping("/bff/profile") @ResponseBody
    ResponseEntity<?> update(Authentication principal, HttpServletRequest request, HttpServletResponse response, @RequestBody ProfileUpdate body) {
        return proxy(principal, request, response, "/api/profile", body);
    }
    @GetMapping({"/bff/v1/me", "/bff/v1/me/applications", "/bff/v1/me/wallet", "/bff/v1/me/wallet/ledger", "/bff/v1/me/credit-requests"}) @ResponseBody
    ResponseEntity<?> platform(Authentication principal,HttpServletRequest request,HttpServletResponse response,
            @RequestParam(required=false) Long before) {
        String path=request.getRequestURI().substring(4);
        if(before!=null) path+="?before="+before;
        return proxy(principal,request,response,path,null);
    }
    @PostMapping("/bff/v1/me/credit-requests") @ResponseBody
    ResponseEntity<?> creditRequest(Authentication principal,HttpServletRequest request,HttpServletResponse response,
            @RequestBody local.identity.demo.wallet.WalletTypes.CreditRequest body) {
        return proxy(principal,request,response,"/v1/me/credit-requests",body);
    }
    private ResponseEntity<?> proxy(Authentication principal, HttpServletRequest request, HttpServletResponse response, String path, Object body) {
        HttpSession session = request.getSession(false);
        if (session == null || !(principal instanceof OAuth2AuthenticationToken)) return ResponseEntity.status(401).build();
        try {
            synchronized (session) {
                var client = clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("consumer").principal(principal)
                        .attribute(HttpServletRequest.class.getName(), request).attribute(HttpServletResponse.class.getName(), response).build());
                if (client == null) return ResponseEntity.status(401).build();
                var call = api.method(body == null ? HttpMethod.GET : HttpMethod.POST).uri("http://127.0.0.1:" + port + path)
                        .headers(h -> h.setBearerAuth(client.getAccessToken().getTokenValue()));
                if (body != null) call.contentType(MediaType.APPLICATION_JSON).body(body);
                Map<?, ?> user = call.retrieve().body(Map.class);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(user);
            }
        } catch (OAuth2AuthenticationException e) {
            session.invalidate(); return ResponseEntity.status(401).build();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 401) session.invalidate();
            return ResponseEntity.status(e.getStatusCode()).build();
        } catch (Exception e) { return ResponseEntity.status(502).body(Map.of("error", "业务服务暂不可用")); }
    }
    @GetMapping({"/api/me", "/api/employee/me"}) @ResponseBody
    ResponseEntity<?> apiMe(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var account = status.account(jwt.getTokenValue());
        if (request.getRequestURI().startsWith("/api/employee/") && !Boolean.TRUE.equals(account.get("employee")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return profile(jwt, account);
    }
    @PostMapping("/api/profile") @ResponseBody
    ResponseEntity<?> apiUpdate(@AuthenticationPrincipal Jwt jwt, @RequestBody ProfileUpdate body) {
        var account = status.account(jwt.getTokenValue());
        users.rename(jwt, body.displayName());
        return profile(jwt, account);
    }
    private ResponseEntity<?> profile(Jwt jwt, Map<?, ?> account) {
        var result = new LinkedHashMap<String, Object>(users.profile(jwt));
        result.putAll(Map.of("subject", jwt.getSubject(), "issuer", jwt.getIssuer().toString(), "realm", "consumer",
            "name", Objects.toString(jwt.getClaimAsString("preferred_username"), jwt.getSubject()),
            "email", Objects.toString(jwt.getClaimAsString("email"), ""), "product", productId,
            "companyLinked", Boolean.TRUE.equals(account.get("companyLinked")), "employee", Boolean.TRUE.equals(account.get("employee"))));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
