package local.identity;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.*;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;

/** Placed after the Cookie / Broker alternative subflow, so SSO cannot bypass admission. */
public final class WorkforceGuard implements Authenticator, AuthenticatorFactory {
    @Override public void authenticate(AuthenticationFlowContext c) {
        if (WorkforceAdmission.allowed(c.getRealm(), c.getUser())) c.success();
        else c.failure(AuthenticationFlowError.ACCESS_DENIED, WeComIdentityProvider.rejected(403));
    }
    @Override public void action(AuthenticationFlowContext c) { authenticate(c); }
    @Override public boolean requiresUser() { return true; }
    @Override public boolean configuredFor(KeycloakSession s, RealmModel r, UserModel u) { return true; }
    @Override public void setRequiredActions(KeycloakSession s, RealmModel r, UserModel u) { }
    @Override public Authenticator create(KeycloakSession s) { return this; }
    @Override public String getId() { return "identity-workforce-guard"; }
    @Override public String getDisplayType() { return "Workforce admission"; }
    @Override public String getReferenceCategory() { return "workforce"; }
    @Override public String getHelpText() { return "Require an enabled, pre-provisioned employee with application access."; }
    @Override public boolean isConfigurable() { return false; }
    @Override public boolean isUserSetupAllowed() { return false; }
    @Override public List<ProviderConfigProperty> getConfigProperties() { return List.of(); }
    @Override public AuthenticationExecutionModel.Requirement[] getRequirementChoices() { return new AuthenticationExecutionModel.Requirement[]{AuthenticationExecutionModel.Requirement.REQUIRED}; }
    @Override public void init(Config.Scope s) { }
    @Override public void postInit(KeycloakSessionFactory f) { }
    @Override public void close() { }
}
