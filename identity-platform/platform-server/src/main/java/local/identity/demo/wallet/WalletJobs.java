package local.identity.demo.wallet;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import local.identity.demo.account.UpstreamEligibility;

@Component
public class WalletJobs {
    private static final Logger log=LoggerFactory.getLogger(WalletJobs.class);
    private final CreditRequests requests;
    private final UpstreamEligibility eligibility;
    private final WalletService wallets;
    public WalletJobs(CreditRequests requests,UpstreamEligibility eligibility,WalletService wallets) { this.requests=requests;this.eligibility=eligibility;this.wallets=wallets; }
    @Scheduled(fixedDelayString="${identity.credit-approval-poll-ms:30000}",initialDelay=30000)
    public void approvals() {
        for(UUID id:requests.due()) {
            try { eligibility.check(requests.owner(id)); requests.decide(id,true,"system","scheduled_approval"); }
            catch(Exception error) { log.error("Credit approval failed requestId={}",id,error); }
        }
    }
    @Scheduled(fixedDelayString="${identity.credit-refresh-poll-ms:60000}",initialDelay=60000)
    public void refresh() {
        for(UUID user:wallets.refreshDue()) {
            try {eligibility.check(user);wallets.balance(user);}
            catch(Exception error){log.warn("Daily refresh skipped userId={} reason={}",user,error.getClass().getSimpleName());}
        }
    }
}
