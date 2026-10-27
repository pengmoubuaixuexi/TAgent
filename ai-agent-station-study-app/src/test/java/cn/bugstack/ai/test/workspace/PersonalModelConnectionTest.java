package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.model.*;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.infrastructure.security.*;
import cn.bugstack.ai.infrastructure.adapter.repository.WorkspaceAccessRepository;
import cn.bugstack.ai.trigger.workspace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** In-memory database, synthetic credentials and stubbed DNS/probe; no provider or live database calls. */
public class PersonalModelConnectionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class Transactions {}
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate db;
    private ModelSecretCipher cipher;
    private PersonalModelEndpointPolicy endpoints;
    private ModelProviderAccessService access;
    private PersonalModelConnectionService service;
    private ModelConnectionProbe probe;
    private final ObjectMapper json=new ObjectMapper();

    @Before public void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:byok_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        db=new JdbcTemplate(source);
        db.execute("CREATE TABLE ai_client_api(api_id VARCHAR(64) PRIMARY KEY,owner_user_id VARCHAR(64),base_url VARCHAR(500),api_key VARCHAR(4096),secret_ref VARCHAR(64),completions_path VARCHAR(100),embeddings_path VARCHAR(100),status INT,create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE ai_client_model(model_id VARCHAR(64) PRIMARY KEY,api_id VARCHAR(64),owner_user_id VARCHAR(64),model_name VARCHAR(128),model_type VARCHAR(32),model_usage VARCHAR(32),tier VARCHAR(32),capabilities_json VARCHAR(100),status INT,platform_enabled INT,source_model_id VARCHAR(64),create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE ai_model_secret(secret_ref VARCHAR(64) PRIMARY KEY,api_id VARCHAR(64) UNIQUE,owner_user_id VARCHAR(64),display_name VARCHAR(100),ciphertext TEXT,credential_version INT,status INT,verified_at TIMESTAMP,create_time TIMESTAMP DEFAULT NOW(),update_time TIMESTAMP DEFAULT NOW())");
        db.execute("CREATE TABLE ai_user_model_policy(user_id VARCHAR(64) PRIMARY KEY,platform_auxiliaries_enabled INT,update_time TIMESTAMP DEFAULT NOW())");
        db.execute("CREATE TABLE ai_model_catalog(id BIGINT AUTO_INCREMENT PRIMARY KEY,owner_scope VARCHAR(64),api_id VARCHAR(64),model_name VARCHAR(64),tier VARCHAR(16),sort_order INT DEFAULT 100,capabilities_json VARCHAR(500),enabled INT DEFAULT 1,UNIQUE(owner_scope,api_id,model_name))");
        cipher=new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        endpoints=new PersonalModelEndpointPolicy("api.openai.com") {
            @Override protected InetAddress[] resolve(String host) throws UnknownHostException {return new InetAddress[]{InetAddress.getByAddress(new byte[]{1,1,1,1})};}
        };
        access=new ModelProviderAccessService(db,cipher,endpoints);
        probe=mock(ModelConnectionProbe.class);
        context=new AnnotationConfigApplicationContext();
        context.registerBean("mysqlJdbcTemplate",JdbcTemplate.class,()->db);
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.getBeanFactory().registerSingleton("cipher",cipher);
        context.getBeanFactory().registerSingleton("endpoints",endpoints);
        context.getBeanFactory().registerSingleton("access",access);
        context.getBeanFactory().registerSingleton("probe",probe);
        context.register(Transactions.class,PersonalModelConnectionService.class);
        context.refresh();service=context.getBean(PersonalModelConnectionService.class);
    }
    @After public void close(){MDC.clear();if(context!=null)context.close();}
    private Map<String,Object> create(String user,String key){
        var body=json.createObjectNode().put("name","Personal").put("modelName","test-chat")
                .put("baseUrl","https://api.openai.com/v1").put("apiKey",key).put("allowPlatformAuxiliaries",false);
        return service.create(user,body);
    }
    @Test public void storesCiphertextAndReturnsOnlyMetadata() throws Exception {
        var saved=create("alice","synthetic-one");
        String api=(String)saved.get("apiId");
        String encrypted=db.queryForObject("SELECT ciphertext FROM ai_model_secret",String.class);
        assertFalse(encrypted.contains("synthetic-one"));
        assertEquals("managed",db.queryForObject("SELECT api_key FROM ai_client_api",String.class));
        assertEquals("synthetic-one",access.apiKeyForRequest(api,"alice"));
        String response=json.writeValueAsString(service.list("alice"));
        assertFalse(response.contains(encrypted));assertFalse(response.contains("synthetic-one"));
        assertTrue(response.contains("maskedKey"));assertTrue(((List<?>)service.list("bob").get("connections")).isEmpty());
    }
    @Test public void ciphertextCannotMoveAcrossOwnerReferenceOrVersion(){
        String encrypted=cipher.encrypt("alice","ref",1,"synthetic-key");
        assertThrows(IllegalStateException.class,()->cipher.decrypt("bob","ref",1,encrypted));
        assertThrows(IllegalStateException.class,()->cipher.decrypt("alice","other",1,encrypted));
        assertThrows(IllegalStateException.class,()->cipher.decrypt("alice","ref",2,encrypted));
    }
    @Test public void crossOwnerCannotUseRotateRevokeOrVerify(){
        String api=(String)create("alice","synthetic-key").get("apiId");
        assertThrows(SecurityException.class,()->access.apiKeyForRequest(api,"bob"));
        assertThrows(ResponseStatusException.class,()->service.rotate("bob",api,json.createObjectNode().put("apiKey","other")));
        assertThrows(ResponseStatusException.class,()->service.revoke("bob",api));
        assertThrows(ResponseStatusException.class,()->service.verify("bob",api));
        verifyNoInteractions(probe);
    }
    @Test public void cachedInterceptorResolvesRotationAndRevocationEveryAttempt() throws Exception {
        String api=(String)create("alice","synthetic-one").get("apiId");
        var interceptor=new ModelCredentialRequestAuthorizer(access).synchronous(api);
        var headers=new HttpHeaders();var request=mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);when(request.getURI()).thenReturn(URI.create("https://api.openai.com/v1/chat/completions"));
        var next=mock(ClientHttpRequestExecution.class);
        MDC.put("userId","alice");
        interceptor.intercept(request,new byte[0],next);assertEquals("Bearer synthetic-one",headers.getFirst("Authorization"));
        service.rotate("alice",api,json.createObjectNode().put("apiKey","synthetic-two"));
        interceptor.intercept(request,new byte[0],next);assertEquals("Bearer synthetic-two",headers.getFirst("Authorization"));
        service.revoke("alice",api);
        assertThrows(SecurityException.class,()->interceptor.intercept(request,new byte[0],next));
        verify(next,times(2)).execute(request,new byte[0]);
    }
    @Test public void revokedConnectionCanOnlyResumeWithNewKey(){
        String api=(String)create("alice","synthetic-one").get("apiId");service.revoke("alice",api);
        assertThrows(SecurityException.class,()->access.apiKeyForRequest(api,"alice"));
        service.rotate("alice",api,json.createObjectNode().put("apiKey","synthetic-two"));
        assertEquals("synthetic-two",access.apiKeyForRequest(api,"alice"));
        assertEquals(3,db.queryForObject("SELECT credential_version FROM ai_model_secret",Integer.class).intValue());
    }
    @Test public void verificationIsExplicitAndInvalidatedOnRotation(){
        String api=(String)create("alice","synthetic-one").get("apiId");verifyNoInteractions(probe);
        service.verify("alice",api);verify(probe).verify("https://api.openai.com/v1","synthetic-one","test-chat");
        assertNotNull(db.queryForObject("SELECT verified_at FROM ai_model_secret",java.sql.Timestamp.class));
        service.rotate("alice",api,json.createObjectNode().put("apiKey","synthetic-two"));
        assertNull(db.queryForObject("SELECT verified_at FROM ai_model_secret",java.sql.Timestamp.class));
    }
    @Test public void verificationCannotCertifyAnOlderCredentialVersion(){
        String api=(String)create("alice","synthetic-one").get("apiId");
        doAnswer(invocation->{service.rotate("alice",api,json.createObjectNode().put("apiKey","synthetic-two"));return null;})
                .when(probe).verify(anyString(),anyString(),anyString());
        assertThrows(ResponseStatusException.class,()->service.verify("alice",api));
        assertNull(db.queryForObject("SELECT verified_at FROM ai_model_secret",java.sql.Timestamp.class));
    }
    @Test public void deniedAuxiliaryNeverBorrowsPersonalKeyAndLegacyDefaultsRemain(){
        access.requirePlatformAuxiliary("legacy-user","EMBEDDING");
        create("alice","synthetic-key");assertThrows(SecurityException.class,()->access.requirePlatformAuxiliary("alice","EMBEDDING"));
        service.setAuxiliaryPolicy("alice",true);access.requirePlatformAuxiliary("alice","EMBEDDING");
        assertThrows(SecurityException.class,()->access.requirePlatformAuxiliary(null,"CHAT_HELPER"));
    }
    @Test public void unavailableMasterKeyFailsBeforeWrites(){
        var unavailable=new PersonalModelConnectionService(db,new ModelSecretCipher(""),endpoints,access,probe);
        var body=json.createObjectNode().put("name","x").put("modelName","chat").put("baseUrl","https://api.openai.com")
                .put("apiKey","synthetic").put("allowPlatformAuxiliaries",false);
        assertThrows(IllegalStateException.class,()->unavailable.create("alice",body));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_client_api",Integer.class).intValue());
    }
    @Test public void partialCreationRollsBackAllConnectionRows(){
        db.execute("DROP TABLE ai_user_model_policy");
        assertThrows(org.springframework.dao.DataAccessException.class,()->create("alice","synthetic"));
        for(String table:List.of("ai_client_api","ai_client_model","ai_model_secret"))
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class).intValue());
    }
    @Test public void ownPrivateModelsAreVisibleButCannotBecomePlatformModels(){
        String own=(String)create("alice","synthetic-a").get("modelId");create("bob","synthetic-b");
        db.update("UPDATE ai_client_model SET platform_enabled=1");
        var workspace=new WorkspaceService(db,json,null,null,null,null,null,null);
        assertEquals(List.of(own),workspace.models("alice").stream().map(row->row.get("modelId")).toList());
        assertTrue(workspace.models().isEmpty());assertTrue(new WorkspaceAccessRepository(db).platformModelIds().isEmpty());
    }
    @Test public void changedEndpointCannotLeakKeyToCachedOldTarget(){
        String api=(String)create("alice","synthetic-key").get("apiId");
        assertThrows(SecurityException.class,()->access.apiKeyForRequest(api,"alice",URI.create("https://elsewhere.example/v1/chat/completions")));
        assertThrows(SecurityException.class,()->access.apiKeyForRequest(api,"alice",URI.create("https://api.openai.com/other/chat/completions")));
    }
    @Test public void endpointPolicyRejectsPrivateDnsAndNonHttps() throws Exception {
        assertThrows(IllegalArgumentException.class,()->endpoints.validate("http://api.openai.com"));
        assertThrows(IllegalArgumentException.class,()->endpoints.validate("https://secret@api.openai.com"));
        assertThrows(IllegalArgumentException.class,()->endpoints.validate("https://localhost"));
        var privateDns=new PersonalModelEndpointPolicy("api.openai.com"){
            @Override protected InetAddress[] resolve(String host)throws UnknownHostException{return new InetAddress[]{InetAddress.getByName("fc00::1")};}
        };
        assertThrows(IllegalArgumentException.class,()->privateDns.requirePublicDns("https://api.openai.com"));
    }
    @Test public void standaloneScopeOwnsBudgetAndSystemPermissionEndsWithScope(){
        MDC.put("previous","kept");String systemOwner;
        try(var scope=ModelInvocationScope.openSystem(new RunBudgetRegistry(),ModelInvocationScope.SystemPurpose.SYSTEM_MAINTENANCE)){
            systemOwner=ModelInvocationScope.currentUser();assertTrue(ModelInvocationScope.isSystemOwner(systemOwner));
            access.requirePlatformAuxiliary(systemOwner,"EMBEDDING");
            assertThrows(SecurityException.class,()->ModelInvocationScope.open(new RunBudgetRegistry(),"alice","OTHER"));
        }
        assertFalse(ModelInvocationScope.isSystemOwner(systemOwner));assertNull(MDC.get("runId"));assertEquals("kept",MDC.get("previous"));
    }
    @Test public void streamingUsesTrustedRunOwnerAcrossThreadBoundary(){
        String api=(String)create("alice","synthetic-stream-key").get("apiId"),run="stream-"+UUID.randomUUID();
        new RunBudgetRegistry().start(run,"alice");MDC.put("userId","bob");
        var request=org.springframework.web.reactive.function.client.ClientRequest.create(HttpMethod.POST,
                URI.create("https://api.openai.com/v1/chat/completions")).build();
        var seen=new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            new ModelCredentialRequestAuthorizer(access).streaming(api).filter(request,r->{
                seen.set(r.headers().getFirst("Authorization"));
                return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(HttpStatus.OK).build());
            }).contextWrite(c->c.put("agent.reasoning_run",run)).block(java.time.Duration.ofSeconds(5));
            assertEquals("Bearer synthetic-stream-key",seen.get());
        } finally {RunBudgetRegistry.finish(run);}
    }
    @Test public void missingIdentityStopsStreamingBeforeNetwork(){
        String api=(String)create("alice","synthetic-key").get("apiId");
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        var request=org.springframework.web.reactive.function.client.ClientRequest.create(HttpMethod.POST,
                URI.create("https://api.openai.com/v1/chat/completions")).build();
        assertThrows(SecurityException.class,()->new ModelCredentialRequestAuthorizer(access).streaming(api).filter(request,r->{
            called.set(true);return reactor.core.publisher.Mono.empty();
        }).block(java.time.Duration.ofSeconds(5)));
        assertFalse(called.get());
    }
}
