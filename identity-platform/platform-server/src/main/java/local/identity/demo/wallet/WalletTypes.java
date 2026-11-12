package local.identity.demo.wallet;

import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class WalletTypes {
    private WalletTypes() { }
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public enum Policy { EMPLOYEE, CONSUMER }
    public enum Mode { FIXED, TOKEN }
    public record Rules(UUID user, Policy policy, boolean enabled, int epoch, long daily, long initial,
                        LocalTime resetTime, boolean requests, long maxRequest, long maxDaily,
                        int immediate, int delayed, int delayMinutes) {
        public String period(Instant instant) {
            if (policy == Policy.CONSUMER) return "persistent";
            var current = instant.atZone(ZONE);
            return (current.toLocalTime().isBefore(resetTime) ? current.toLocalDate().minusDays(1) : current.toLocalDate()).toString();
        }
        public Instant expires(Instant instant) {
            return policy == Policy.CONSUMER ? null : LocalDate.parse(period(instant)).plusDays(1).atTime(resetTime).atZone(ZONE).toInstant();
        }
    }
    public record Bucket(UUID id, long balance, long reserved, Instant expires) { }
    public record Reserve(String operationId, String requestDigest, String localUserId, Mode mode, String amount, String context) {
        public long validate() {
            bounded(operationId, 191); bounded(localUserId, 191); bounded(context, 4000);
            if (mode == null || requestDigest == null || !requestDigest.matches("[a-f0-9]{64}")) throw error(400, "invalid_operation");
            long value = credits(amount);
            if (mode == Mode.TOKEN && value != 0) throw error(400, "token_reservation_must_be_zero");
            return value;
        }
    }
    public record Settlement(String amount, String evidence) { }
    public record Release(String evidence) { }
    public record CreditRequest(UUID requestId, String amount, String reason) { }
    public static long credits(String input) {
        if (input == null || !input.matches("0|[1-9][0-9]{0,17}")) throw error(400, "invalid_credit_amount");
        return Long.parseLong(input);
    }
    public static String bounded(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length || value.codePoints().anyMatch(Character::isISOControl))
            throw error(400, "invalid_text");
        return value;
    }
    public static ResponseStatusException error(int status, String code) {
        return new ResponseStatusException(HttpStatus.valueOf(status), code);
    }
}
