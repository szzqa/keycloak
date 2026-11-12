package local.identity.demo.account;

import static local.identity.demo.wallet.WalletTypes.*;
import java.util.*;
import local.identity.demo.wallet.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/me")
public class PlatformController {
    private final PlatformAccounts accounts;
    private final WalletService wallets;
    private final CreditRequests requests;
    public PlatformController(PlatformAccounts accounts,WalletService wallets,CreditRequests requests) {this.accounts=accounts;this.wallets=wallets;this.requests=requests;}
    @GetMapping public Object me(@AuthenticationPrincipal Jwt jwt) { return accounts.user(jwt); }
    @GetMapping("/applications") public Object applications(@AuthenticationPrincipal Jwt jwt) { return Map.of("items",accounts.applications(accounts.user(jwt))); }
    @GetMapping("/wallet") public Object wallet(@AuthenticationPrincipal Jwt jwt) { return wallets.balance(accounts.user(jwt).id()); }
    @GetMapping("/wallet/ledger") public Object ledger(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Long before) { return wallets.ledger(accounts.user(jwt).id(),before); }
    @GetMapping("/credit-requests") public Object requests(@AuthenticationPrincipal Jwt jwt) { return requests.history(accounts.user(jwt).id()); }
    @PostMapping("/credit-requests") public Object request(@AuthenticationPrincipal Jwt jwt,@RequestBody CreditRequest input) { return requests.apply(accounts.user(jwt).id(),input); }
}
