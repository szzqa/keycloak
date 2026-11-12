package local.identity;

import java.nio.file.*;
import java.io.InputStream;
import java.util.Properties;
import org.keycloak.Keycloak;

/** Run from identity-platform; scripts/setup.ps1 generates the local settings. */
public final class IdentityKeycloakDev {
    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("identity.home", ".")).toAbsolutePath().normalize();
        Path local = Path.of(System.getProperty("identity.local",home.resolve(".local").toString())).toAbsolutePath().normalize();
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(local.resolve("keycloak.properties"))) { config.load(in); }
        config.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
        Keycloak.builder().setVersion("999.0.0-SNAPSHOT").setHomeDir(local.resolve("keycloak-home"))
            .addDependency("local.identity", "keycloak-identity-extensions", "1.0.0-SNAPSHOT")
            .addDependency("local.identity", "identity-theme", "1.0.0-SNAPSHOT")
            .start(args.length==0 ? new String[]{"start-dev", "--http-host=127.0.0.1", "--http-port=8180", "--hostname=http://127.0.0.1:8180"} : args);
    }
}
