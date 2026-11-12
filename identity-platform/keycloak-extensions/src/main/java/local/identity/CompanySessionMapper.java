package local.identity;

import java.util.List;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.provider.*;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.JsonWebToken;

/** Carries verified upstream session proof, never a user-editable employee flag. */
public final class CompanySessionMapper extends AbstractIdentityProviderMapper {
    static final String SUBJECT = "identity.company.subject";
    static final String SESSION = "identity.company.session";
    public String getId() { return "identity-company-session"; }
    public String[] getCompatibleProviders() { return new String[]{"oidc"}; }
    public String getDisplayCategory() { return "Identity"; }
    public String getDisplayType() { return "Verified company session"; }
    public String getHelpText() { return "Retains a verified workforce session for employee authorization."; }
    public List<ProviderConfigProperty> getConfigProperties() { return List.of(); }
    @Override public void preprocessFederatedIdentity(KeycloakSession session, RealmModel realm,
            IdentityProviderMapperModel mapper, BrokeredIdentityContext context) {
        Object value = context.getContextData().get(OIDCIdentityProvider.VALIDATED_ID_TOKEN);
        if (!"consumer".equals(realm.getName()) || !"company".equals(mapper.getIdentityProviderAlias())
                || !(value instanceof JsonWebToken token) || !(token.getOtherClaims().get("sid") instanceof String)
                || !context.getId().equals(token.getSubject()))
            throw new IdentityBrokerException("Missing verified company session");
        context.setSessionNote(SUBJECT, token.getSubject());
        context.setSessionNote(SESSION, (String) token.getOtherClaims().get("sid"));
    }
}
