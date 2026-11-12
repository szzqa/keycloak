package local.identity;

import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.services.resource.*;

public final class PlatformAccountResourceFactory implements RealmResourceProviderFactory {
    public String getId() { return "identity-platform"; }
    public RealmResourceProvider create(KeycloakSession session) { return new PlatformAccountResource(session); }
    public void init(Config.Scope config) { }
    public void postInit(KeycloakSessionFactory factory) { }
    public void close() { }
}
