package local.identity;

import org.keycloak.authentication.AuthenticatorUtil;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.requiredactions.UpdatePassword;
import org.keycloak.models.KeycloakSession;

/** Keep native validation/storage, then revoke other sessions on successful password change. */
public final class ForcedPasswordUpdate extends UpdatePassword {
    @Override public int order() { return 100; }
    @Override public RequiredActionProvider create(KeycloakSession session) { return new ForcedPasswordUpdate(); }
    @Override public void processAction(RequiredActionContext context) {
        super.processAction(context);
        if (context.getStatus() == RequiredActionContext.Status.SUCCESS
                && "true".equals(context.getRealm().getAttribute("identity.force-password-logout"))) {
            AuthenticatorUtil.logoutOtherSessions(context);
        }
    }
}
