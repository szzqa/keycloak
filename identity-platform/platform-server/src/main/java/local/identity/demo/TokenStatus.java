package local.identity.demo;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TokenStatus {
    private final RestClient client;
    private final String endpoint;
    public TokenStatus(@Value("${identity.consumer-issuer}") String issuer) {
        endpoint = issuer + "/identity-platform/account";
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().requestFactory(factory).build();
    }
    public Map<?, ?> account(String token) {
        try {
            var result = client.get().uri(endpoint).headers(h -> h.setBearerAuth(token)).retrieve().body(Map.class);
            if (result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            return result;
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
