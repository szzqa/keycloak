package local.identity;

import jakarta.enterprise.inject.Vetoed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.Set;
import org.keycloak.models.*;
import org.keycloak.services.managers.*;
import org.keycloak.services.resource.RealmResourceProvider;

@Provider @Vetoed
public final class PlatformAccountResource implements RealmResourceProvider {
    private final KeycloakSession session;
    public PlatformAccountResource(KeycloakSession session) { this.session = session; }
    public Object getResource() { return this; }
    public void close() { }

    @GET @Path("eligibility/{subject}") @Produces(MediaType.APPLICATION_JSON)
    public Response eligibility(@PathParam("subject") String subject) {
        var realm=session.getContext().getRealm();
        if(!"consumer".equals(realm.getName()))throw new NotFoundException();
        var auth=new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        if(auth==null || !auth.getUser().isEnabled() || auth.getUser().getServiceAccountClientLink()==null)throw new NotAuthorizedException("Bearer");
        var client=realm.getClientByClientId(auth.getToken().getIssuedFor());
        if(client==null || !client.isEnabled() || !client.getId().equals(auth.getUser().getServiceAccountClientLink())
            || !"true".equals(client.getAttribute("identity.platform.guard")))throw new ForbiddenException();
        var user=session.users().getUserById(realm,subject);
        var link=user==null?null:session.users().getFederatedIdentity(realm,user,"company");
        var workforce=session.realms().getRealmByName("workforce");
        boolean active=user!=null && user.isEnabled();
        boolean employee=active && link!=null && workforce!=null && WorkforceAdmission.allowed(workforce,session.users().getUserById(workforce,link.getUserId()));
        return Response.ok(Map.of("accountEnabled",active,"employeeEligible",employee,"emailVerified",active && user.isEmailVerified()))
            .header("Cache-Control","no-store").build();
    }

    @GET @Path("account") @Produces(MediaType.APPLICATION_JSON)
    public Response account() {
        var realm = session.getContext().getRealm();
        if (!"consumer".equals(realm.getName())) throw new NotFoundException();
        var auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        if (auth == null || auth.getSession() == null || !auth.getUser().isEnabled()) throw new NotAuthorizedException("Bearer");
        var client = realm.getClientByClientId(auth.getToken().getIssuedFor());
        if(client == null || !client.isEnabled() || !"true".equals(client.getAttribute("identity.platform.client")))
            throw new NotAuthorizedException("Bearer");
        var link = session.users().getFederatedIdentity(realm, auth.getUser(), "company");
        boolean employee = false;
        String subject = auth.getSession().getNote(CompanySessionMapper.SUBJECT);
        String sid = auth.getSession().getNote(CompanySessionMapper.SESSION);
        var workforce = session.realms().getRealmByName("workforce");
        boolean eligible = link != null && workforce != null
                && WorkforceAdmission.allowed(workforce, session.users().getUserById(workforce, link.getUserId()));
        if (link != null && link.getUserId().equals(subject) && sid != null && workforce != null) {
            var staff = session.users().getUserById(workforce, subject);
            var upstream = session.sessions().getUserSession(workforce, sid);
            employee = WorkforceAdmission.allowed(workforce, staff) && upstream != null
                    && upstream.getUser().getId().equals(subject)
                    && AuthenticationManager.isSessionValid(workforce, upstream);
        }
        if(!auth.getUser().isEmailVerified() && !eligible) throw new NotAuthorizedException("Bearer");
        return Response.ok(Map.of("companyLinked", link != null, "employee", employee, "employeeEligible", eligible))
                .header("Cache-Control", "no-store").build();
    }
}
