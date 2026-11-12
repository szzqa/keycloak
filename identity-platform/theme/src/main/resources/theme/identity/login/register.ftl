<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
  <#if section == "header">${msg("registerTitle")}
  <#elseif section == "form">
    <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.registrationAction}" method="post">
      <div class="${properties.kcFormGroupClass!}">
        <label for="email" class="${properties.kcLabelClass!}">${msg("email")}</label>
        <input type="email" id="email" name="email" class="${properties.kcInputClass!}" value="${(register.formData.email)!''}" autocomplete="email" required autofocus />
        <#if messagesPerField.existsError('email','username')><span class="${properties.kcInputErrorMessageClass!}" role="alert">${kcSanitize(messagesPerField.getFirstError('email','username'))?no_esc}</span></#if>
      </div>
      <#if passwordRequired??>
        <div class="${properties.kcFormGroupClass!}"><label for="password">${msg("password")}</label><input class="${properties.kcInputClass!}" id="password" type="password" name="password" autocomplete="new-password" required /></div>
        <div class="${properties.kcFormGroupClass!}"><label for="password-confirm">${msg("passwordConfirm")}</label><input class="${properties.kcInputClass!}" id="password-confirm" type="password" name="password-confirm" autocomplete="new-password" required /></div>
      </#if>
      <#if recaptchaRequired??><div class="g-recaptcha" data-sitekey="${recaptchaSiteKey}"></div></#if>
      <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!}" type="submit">${msg("doRegister")}</button>
      <p><a href="${url.loginUrl}">${msg("backToLogin")?no_esc}</a></p>
    </form>
  </#if>
</@layout.registrationLayout>
