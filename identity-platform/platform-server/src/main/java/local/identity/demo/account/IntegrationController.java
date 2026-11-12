package local.identity.demo.account;

import static local.identity.demo.wallet.WalletTypes.*;
import java.util.*;
import local.identity.demo.wallet.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class IntegrationController {
    private final PlatformAccounts accounts; private final WalletService wallets; private final CreditRequests requests; private final JwtDecoder decoder;
    private final UpstreamEligibility eligibility;
    public IntegrationController(PlatformAccounts accounts,WalletService wallets,CreditRequests requests,JwtDecoder decoder,UpstreamEligibility eligibility) {
        this.accounts=accounts;this.wallets=wallets;this.requests=requests;this.decoder=decoder;this.eligibility=eligibility;
    }
    private PlatformAccounts.App app(Jwt service) { return accounts.application(service.getClaimAsString("azp")); }
    private PlatformAccounts.App completionApp(Jwt service) {return accounts.registeredApplication(service.getClaimAsString("azp"),false);}
    private PlatformAccounts.User user(PlatformAccounts.App app,String token) {
        var user=accounts.user(IntegrationSecurity.userToken(decoder,token,app)); accounts.requireAccess(app,user); return user;
    }
    @PostMapping("/app-accounts/resolve") public Object resolve(@AuthenticationPrincipal Jwt service,@RequestHeader("X-User-Token") String token) {
        var app=app(service); return accounts.resolve(app,user(app,token));
    }
    @GetMapping("/me/wallet") public Object wallet(@AuthenticationPrincipal Jwt service,@RequestHeader("X-User-Token") String token) {
        var app=app(service); return wallets.balance(user(app,token).id());
    }
    @GetMapping("/me/wallet/ledger") public Object ledger(@AuthenticationPrincipal Jwt service,@RequestHeader("X-User-Token") String token,@RequestParam(required=false) Long before) {
        var app=app(service); return wallets.ledger(user(app,token).id(),before);
    }
    @GetMapping("/me/credit-requests") public Object requests(@AuthenticationPrincipal Jwt service,@RequestHeader("X-User-Token") String token) {
        var app=app(service); return requests.history(user(app,token).id());
    }
    @PostMapping("/me/credit-requests") public Object request(@AuthenticationPrincipal Jwt service,@RequestHeader("X-User-Token") String token,@RequestBody CreditRequest input) {
        var app=app(service); return requests.apply(user(app,token).id(),input);
    }
    @PostMapping("/wallet/reservations") public Object reserve(@AuthenticationPrincipal Jwt service,@RequestHeader("X-User-Token") String token,@RequestBody Reserve input) {
        var app=app(service); var user=user(app,token); var link=accounts.resolve(app,user);
        if(!Objects.equals(link.get("localUserId"),input.localUserId())) throw error(403,"local_account_mismatch");
        return wallets.reserve(app.id(),user.id(),input);
    }
    @GetMapping("/wallet/operations/{id}") public Object query(@AuthenticationPrincipal Jwt service,@PathVariable String id) { return wallets.query(completionApp(service).id(),id); }
    @PostMapping("/wallet/reservations/cancel") public Object cancel(@AuthenticationPrincipal Jwt service,@RequestBody WalletService.Cancellation input) {
        if(input.reservation()==null)throw error(400,"reservation_required");var app=completionApp(service);
        wallets.cancelUnsubmitted(app.id(),accounts.mappedUser(app,input.reservation().localUserId()),input);return Map.of("status","CANCELLED");
    }
    @PostMapping("/wallet/delegated-reservations") public Object delegated(@AuthenticationPrincipal Jwt service,@RequestBody Reserve input) {
        var scope=service.getClaimAsString("scope");
        if(scope==null || !Arrays.asList(scope.split(" ")).contains("platform.delegate"))throw error(403,"delegation_scope_required");
        var app=app(service);var user=accounts.delegatedUser(app,input.localUserId());
        eligibility.check(user);accounts.requireDelegatedAccess(app,user);
        return wallets.reserve(app.id(),user,input);
    }
    @PostMapping("/wallet/operations/{id}/settle") public Object settle(@AuthenticationPrincipal Jwt service,@PathVariable String id,@RequestBody Settlement input) {return wallets.settle(completionApp(service).id(),id,input);}
    @PostMapping("/wallet/operations/{id}/release") public Object release(@AuthenticationPrincipal Jwt service,@PathVariable String id,@RequestBody Release input) {return wallets.release(completionApp(service).id(),id,input);}
}
