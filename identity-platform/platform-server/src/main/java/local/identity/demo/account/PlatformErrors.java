package local.identity.demo.account;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages={"local.identity.demo.account","local.identity.demo.wallet"})
public class PlatformErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> response(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason()==null?"request_failed":error.getReason()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException error) { return ResponseEntity.status(409).body(Map.of("error","data_conflict")); }
    @ExceptionHandler(ArithmeticException.class)
    ResponseEntity<?> overflow(ArithmeticException error) { return ResponseEntity.badRequest().body(Map.of("error","credit_amount_overflow")); }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,java.time.DateTimeException.class})
    ResponseEntity<?> invalid(Exception error) {return ResponseEntity.badRequest().body(Map.of("error","invalid_request"));}
}
