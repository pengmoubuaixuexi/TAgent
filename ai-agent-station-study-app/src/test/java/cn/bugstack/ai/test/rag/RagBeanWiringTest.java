package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.config.AiAgentConfig;
import cn.bugstack.ai.domain.agent.service.rag.cache.ISemanticCacheService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.RagFileDeletionService;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.PublishedRagDocuments;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.PublishedRagVectorStore;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.RagFilePreviewService;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion.RagIngestionRepository;
import cn.bugstack.ai.infrastructure.dao.IAiClientRagOrderDao;
import cn.bugstack.ai.infrastructure.dao.IAiParentDocumentDao;
import org.junit.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual configuration and PG auto-configuration, isolated from profiles, sockets and real model/DB calls. */
public class RagBeanWiringTest {
    @Configuration(proxyBeanMethods=false)
    static class PostgresTemplates {
        @Bean("pgVectorJdbcTemplate") JdbcTemplate postgres() { return new JdbcTemplate(mock(DataSource.class)); }
    }
    @Configuration(proxyBeanMethods=false)
    static class MysqlTemplates {
        @Bean("mysqlDataSource") DataSource mysqlDataSource() { return mock(DataSource.class); }
        @Bean({"mysqlJdbcTemplate","jdbcTemplate"}) @Primary JdbcTemplate mysql(
                @org.springframework.beans.factory.annotation.Qualifier("mysqlDataSource") DataSource source) { return new JdbcTemplate(source); }
    }
    @Configuration(proxyBeanMethods=false)
    @ImportAutoConfiguration(PgVectorStoreAutoConfiguration.class)
    static class BootPgDefaults { }

    private AnnotationConfigApplicationContext context(boolean jdbcFirst, String semanticCache, boolean postgres, boolean mysql) {
        return context(jdbcFirst,semanticCache,postgres,mysql,Map.of("agent.rag.vector-table","owned_knowledge_fixture"));
    }

    private AnnotationConfigApplicationContext context(boolean jdbcFirst, String semanticCache, boolean postgres, boolean mysql,
                                                       Map<String,Object> tableProperties) {
        var context=new AnnotationConfigApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        Map<String,Object> properties=new HashMap<>(tableProperties);
        if(semanticCache!=null)properties.put("agent.semantic-cache.enabled",semanticCache);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-only",properties));
        var embedding=mock(EmbeddingModel.class);
        // Replace only the production model factory's supplier; all real RAG bean definitions remain.
        // No actual OpenAI client, credentials, network or @SpringBootTest profile is loaded.
        context.addBeanFactoryPostProcessor(factory -> ((AbstractBeanDefinition)factory.getBeanDefinition("embeddingModel"))
                .setInstanceSupplier(()->embedding));
        List<Class<?>> jdbc=new ArrayList<>();
        if(postgres)jdbc.add(PostgresTemplates.class);if(mysql)jdbc.add(MysqlTemplates.class);
        if(jdbcFirst)context.register(jdbc.toArray(Class<?>[]::new));
        context.register(AiAgentConfig.class,PublishedRagDocuments.class,BootPgDefaults.class);
        if(postgres&&mysql) {
            // Already initialized test doubles must not receive the production classes' @Resource fields.
            context.getBeanFactory().registerSingleton("ragOrders",mock(IAiClientRagOrderDao.class));
            context.getBeanFactory().registerSingleton("parentDocuments",mock(IAiParentDocumentDao.class));
            context.getBeanFactory().registerSingleton("keywords",mock(BM25SearchService.class));
            context.getBeanFactory().registerSingleton("ingestionFiles",mock(RagIngestionRepository.class));
            context.register(RagFilePreviewService.class,RagFileDeletionService.class);
        }
        if(!jdbcFirst)context.register(jdbc.toArray(Class<?>[]::new));
        return context;
    }

    private void assertPublishedWiring(boolean jdbcFirst,String enabled) {
        try(var context=context(jdbcFirst,enabled,true,true)) {
            context.refresh();
            var raw=context.getBean("ragVectorStore",PgVectorStore.class);
            var published=context.getBean("vectorStore",PublishedRagVectorStore.class);
            assertSame("Normal VectorStore injection must pass the publication gate",published,context.getBean(VectorStore.class));
            assertSame(raw,ReflectionTestUtils.getField(published,"delegate"));
            assertEquals(Set.of("ragVectorStore"),context.getBeansOfType(PgVectorStore.class).keySet());
            assertEquals(Set.of("ragVectorStore","vectorStore"),context.getBeansOfType(VectorStore.class).keySet());
            var pg=context.getBean("pgVectorJdbcTemplate",JdbcTemplate.class);
            var mysql=context.getBean("mysqlJdbcTemplate",JdbcTemplate.class);
            assertSame(pg,ReflectionTestUtils.getField(raw,"jdbcTemplate"));
            assertSame(mysql,ReflectionTestUtils.getField(context.getBean(PublishedRagDocuments.class),"db"));
            assertEquals("owned_knowledge_fixture",ReflectionTestUtils.getField(raw,"vectorTableName"));
            assertEquals("true".equals(enabled),context.containsBean("semanticCacheService"));
            if("true".equals(enabled)) {
                var cache=context.getBean(ISemanticCacheService.class);
                assertSame(pg,ReflectionTestUtils.getField(cache,"pgVectorJdbcTemplate"));
            }
            verifyNoInteractions(pg.getDataSource(),mysql.getDataSource(),context.getBean("embeddingModel"));
        }
    }

    @Test public void laterJdbcConfigurationStillCreatesPublishedStoreAndEnabledSemanticCache() { assertPublishedWiring(false,"true"); }
    @Test public void earlierJdbcConfigurationCreatesTheSamePublishedStoreAndEnabledSemanticCache() { assertPublishedWiring(true,"true"); }
    @Test public void semanticCacheFollowsItsPropertyRegardlessOfConfigurationOrder() {
        for(boolean first:List.of(false,true))for(String disabled:Arrays.asList("false",null))assertPublishedWiring(first,disabled);
    }
    @Test public void explicitRagTableOverridesLegacySpringTableForAllOperations() {
        assertTableConfiguration(Map.of("agent.rag.vector-table","owned_knowledge_fixture",
                "spring.ai.vectorstore.pgvector.table-name","legacy_fixture"),"owned_knowledge_fixture");
    }
    @Test public void existingSpringTableNameIsHonoredForAllOperations() {
        assertTableConfiguration(Map.of("spring.ai.vectorstore.pgvector.table-name","legacy_fixture"),"legacy_fixture");
    }
    @Test public void absentTablePropertiesKeepOriginalBootDefaultForAllOperations() {
        assertTableConfiguration(Map.of(),"vector_store");
    }
    private void assertTableConfiguration(Map<String,Object> properties,String expected) {
        for(boolean first:List.of(false,true))try(var context=context(first,"false",true,true,properties)) {
            context.refresh();
            assertEquals(expected,ReflectionTestUtils.getField(context.getBean("ragVectorStore"),"vectorTableName"));
            String qualified="\"public\".\""+expected+"\"";
            assertEquals(qualified,ReflectionTestUtils.getField(context.getBean(RagFilePreviewService.class),"table"));
            assertEquals(qualified,ReflectionTestUtils.getField(context.getBean(RagFileDeletionService.class),"vectorTable"));
            assertTrue(context.getBean(VectorStore.class) instanceof PublishedRagVectorStore);
            verifyNoInteractions(context.getBean("pgVectorJdbcTemplate",JdbcTemplate.class).getDataSource(),
                    context.getBean("mysqlDataSource"),context.getBean("embeddingModel"));
        }
    }
    @Test public void missingPostgresFailsClosedInsteadOfSelectingBootsUnfilteredDefault() {
        try(var context=context(false,"false",false,true)) {
            BeansException failure=assertThrows(BeansException.class,context::refresh);
            assertTrue(failure.getMessage().contains("pgVectorJdbcTemplate"));
        }
    }
    @Test public void missingPublicationDatabaseCannotExposeAnUnfilteredStore() {
        try(var context=context(true,"false",true,false)) {
            BeansException failure=assertThrows(BeansException.class,context::refresh);
            assertTrue(failure.getMessage().contains("mysqlJdbcTemplate"));
        }
    }
}
