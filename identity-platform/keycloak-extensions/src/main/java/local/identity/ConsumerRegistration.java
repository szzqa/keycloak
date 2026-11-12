package local.identity;

import java.util.List;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.authentication.forms.RegistrationUserCreation;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.services.messages.Messages;

/** Public registration requires email; verified imported staff may have none. */
public final class ConsumerRegistration extends RegistrationUserCreation {
    @Override public String getId() { return "identity-consumer-registration"; }
    @Override public String getDisplayType() { return "Identity consumer registration"; }
    @Override public void validate(ValidationContext context) {
        var form=context.getHttpRequest().getDecodedFormParameters();
        String email=form.getFirst("email");
        if(email==null || email.isBlank()) {
            context.validationError(form,List.of(new FormMessage("email",Messages.MISSING_EMAIL)));
            return;
        }
        form.putSingle("username",email.trim());
        super.validate(context);
    }
}
