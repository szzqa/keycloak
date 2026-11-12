package local.identity;

import java.util.List;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.models.*;
import org.keycloak.provider.*;

public final class WeComIdentityProviderFactory extends AbstractIdentityProviderFactory<WeComIdentityProvider> {
    @Override public String getName() { return "企业微信（本公司员工）"; }
    @Override public String getId() { return "wecom-corp"; }
    @Override public IdentityProviderModel createConfig() { return new IdentityProviderModel(); }
    @Override public WeComIdentityProvider create(KeycloakSession s, IdentityProviderModel m) { return new WeComIdentityProvider(s, m); }
    @Override public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
            .property().name("corpId").label("CorpID").type(ProviderConfigProperty.STRING_TYPE).helpText("本公司企业 ID").add()
            .property().name("agentId").label("AgentID").type(ProviderConfigProperty.STRING_TYPE).helpText("自建应用 ID").add()
            .property().name("secretEnv").label("Secret 环境变量名").type(ProviderConfigProperty.STRING_TYPE).defaultValue("WECOM_APP_SECRET").helpText("只填写环境变量名称").add()
            .property().name("mockBaseUrl").label("本地模拟地址").type(ProviderConfigProperty.STRING_TYPE).helpText("生产留空；只有显式开发模式允许回环地址").add().build();
    }
}
