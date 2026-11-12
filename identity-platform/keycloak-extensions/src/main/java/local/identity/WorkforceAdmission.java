package local.identity;

import org.keycloak.models.*;

public final class WorkforceAdmission {
    private WorkforceAdmission() { }
    public static boolean allowed(RealmModel realm, UserModel user) {
        if (user == null || !user.isEnabled()) return false;
        String employeeRef = user.getFirstAttribute("employee_ref");
        if (employeeRef == null || employeeRef.isBlank()) return false;
        ClientModel api = realm.getClientByClientId("workforce-api");
        RoleModel role = api == null ? null : api.getRole("employee");
        return role != null && user.hasRole(role);
    }
}
