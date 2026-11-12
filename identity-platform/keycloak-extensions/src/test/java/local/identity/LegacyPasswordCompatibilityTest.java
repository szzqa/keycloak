package local.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.keycloak.credential.hash.Pbkdf2PasswordHashProvider;
import org.keycloak.models.credential.PasswordCredentialModel;

class LegacyPasswordCompatibilityTest {
    // Independent Python hashlib UTF-8 vectors, matching TapCanvas WebCrypto.
    @Test void importsUtf8TextSaltAndHexDigestWithoutChangingPasswords() {
        org.keycloak.common.crypto.CryptoIntegration.init(getClass().getClassLoader());
        String[][] vectors={
            {"TapCanvas-test-123!","3ed313dc3ca1e0913795a35c0443a1677aaf093c38b37d6abc46341a9c00950d"},
            {"中文密码-测试-123!","9b7fb9895eb4dd36656d561e4bafa91f42b5a67dd1be55a180027f55fd8a7efb"},
            {"design-\uD83D\uDD10-\uD840\uDC00-pass","bce10dc000af598a76f8ef761e751d2998443260bb186f99d06cc45f85c36adb"}};
        var provider=new Pbkdf2PasswordHashProvider("pbkdf2-sha256","PBKDF2WithHmacSHA256",210000,0);
        try {
            for(var vector:vectors) {
                var credential=PasswordCredentialModel.createFromValues("pbkdf2-sha256",
                    "3b2eb8bb-d894-4ef6-b75d-a4c26c26364d".getBytes(StandardCharsets.UTF_8),210000,
                    Base64.getEncoder().encodeToString(HexFormat.of().parseHex(vector[1])));
                assertTrue(provider.verify(vector[0],credential));
                assertFalse(provider.verify(vector[0]+"wrong",credential));
            }
        } finally { provider.close(); }
    }
}
