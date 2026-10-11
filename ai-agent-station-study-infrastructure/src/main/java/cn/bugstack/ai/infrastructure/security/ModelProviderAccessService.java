package cn.bugstack.ai.infrastructure.security;

import cn.bugstack.ai.domain.agent.service.model.ModelProviderAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import java.util.Objects;

@Service
public final class ModelProviderAccessService implements ModelProviderAccess {
    private final JdbcTemplate db;
    private final ModelSecretCipher cipher;
    private final PersonalModelEndpointPolicy endpoints;
    public ModelProviderAccessService(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate db, ModelSecretCipher cipher, PersonalModelEndpointPolicy endpoints) {
        this.db=db; this.cipher=cipher; this.endpoints=endpoints;
    }
    @Override public String apiKeyForRequest(String apiId,String user) {
        return apiKeyForRequest(apiId,user,null);
    }
    @Override public String apiKeyForRequest(String apiId,String user,java.net.URI requestUri) {
        if(user==null || user.isBlank()) throw new SecurityException("缺少已认证的模型调用身份");
        var rows=db.queryForList("""
                SELECT a.owner_user_id,a.status,a.api_key,a.secret_ref,a.base_url,s.owner_user_id AS secret_owner,
                       s.ciphertext,s.credential_version,s.status AS secret_status
                FROM ai_client_api a LEFT JOIN ai_model_secret s ON s.secret_ref=a.secret_ref
                WHERE a.api_id=?
                """,apiId);
        if(rows.size()!=1 || ((Number)rows.get(0).get("status")).intValue()!=1) throw new SecurityException("模型连接已停用或不存在");
        var row=rows.get(0);
        String ref=(String)row.get("secret_ref");
        if(ref!=null && !ref.isBlank()) {
            if(user==null || !user.equals(row.get("owner_user_id")) || !user.equals(row.get("secret_owner"))
                    || row.get("secret_status")==null || ((Number)row.get("secret_status")).intValue()!=1)
                throw new SecurityException("无权使用该个人模型连接，或密钥已撤销");
            endpoints.requirePublicDns((String)row.get("base_url"));
            if(requestUri!=null) endpoints.requireRequestTarget((String)row.get("base_url"),requestUri);
            String key=cipher.decrypt(user,ref,((Number)row.get("credential_version")).intValue(),(String)row.get("ciphertext"));
            LoggerFactory.getLogger("model.provider").info("providerSource=USER_BYOK credentialOwner={} apiId={} credentialVersion={}",user,apiId,row.get("credential_version"));
            return key;
        }
        // Existing administrator-owned connections can serve explicitly published platform models.
        Integer platform=db.queryForObject("SELECT COUNT(*) FROM ai_client_model WHERE api_id=? AND platform_enabled=1 AND status=1",Integer.class,apiId);
        if(!Objects.equals(user,row.get("owner_user_id")) && (platform==null || platform==0))
            throw new SecurityException("无权使用该模型连接");
        String key=(String)row.get("api_key");
        if(key==null || key.isBlank()) throw new IllegalStateException("模型连接未配置密钥");
        LoggerFactory.getLogger("model.provider").info("providerSource=PLATFORM credentialOwner={} apiId={}",row.get("owner_user_id"),apiId);
        return key;
    }
    @Override public void requirePlatformAuxiliary(String user,String purpose) {
        if(user==null || user.isBlank()) throw new SecurityException("缺少平台辅助模型调用身份");
        if(cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope.isSystemOwner(user)) {
            LoggerFactory.getLogger("model.provider").info("providerSource=SYSTEM auxiliaryPurpose={} requestedBy={}",purpose,user);
            return;
        }
        var policy=db.queryForList("SELECT platform_auxiliaries_enabled FROM ai_user_model_policy WHERE user_id=?",user);
        if(!policy.isEmpty() && ((Number)policy.get(0).get("platform_auxiliaries_enabled")).intValue()!=1)
            throw new SecurityException("当前账号未允许平台辅助模型服务（"+purpose+"），请在模型设置中启用后重试");
        LoggerFactory.getLogger("model.provider").info("providerSource=PLATFORM auxiliaryPurpose={} requestedBy={}",purpose,user);
    }
}
