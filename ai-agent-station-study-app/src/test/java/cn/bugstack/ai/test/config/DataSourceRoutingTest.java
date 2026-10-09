package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.DataSourceConfig;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import cn.bugstack.ai.domain.agent.service.security.WorkspaceMcpPolicy;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import cn.bugstack.ai.infrastructure.adapter.repository.AgentRepository;
import cn.bugstack.ai.infrastructure.adapter.repository.WorkspaceAccessRepository;
import cn.bugstack.ai.infrastructure.adapter.repository.cache.SemanticCacheService;
import cn.bugstack.ai.infrastructure.adapter.repository.cache.ToolVectorStore;
import cn.bugstack.ai.trigger.http.ObserveController;
import cn.bugstack.ai.trigger.http.admin.util.AdminConfigurationOwnership;
import cn.bugstack.ai.trigger.job.AgentTaskJob;
import cn.bugstack.ai.trigger.workspace.WorkspaceDraftService;
import cn.bugstack.ai.trigger.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/** Exercises production dual-database wiring, without opening a socket or using business data. */
public class DataSourceRoutingTest {
    private ApplicationContextRunner productionDataSources() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DataSourceConfig.class)
                .withConfiguration(AutoConfigurations.of(
                        JdbcTemplateAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.mysql.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.datasource.mysql.url=jdbc:mysql://127.0.0.1:1/routing_test_only",
                        "spring.datasource.mysql.username=routing_test",
                        "spring.datasource.mysql.password=unused",
                        "spring.datasource.mysql.hikari.minimum-idle=0",
                        "spring.datasource.pgvector.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.pgvector.url=jdbc:postgresql://127.0.0.1:1/routing_test_only",
                        "spring.datasource.pgvector.username=routing_test",
                        "spring.datasource.pgvector.password=unused",
                        "spring.datasource.pgvector.hikari.minimum-idle=0");
    }

    @Test
    public void templatesMyBatisAndTransactionsUseTheIntendedDataSource() {
        productionDataSources().run(context -> {
            assertNull("Production data source context must start", context.getStartupFailure());
            DataSource mysql = context.getBean("mysqlDataSource", DataSource.class);
            DataSource postgres = context.getBean("pgVectorDataSource", DataSource.class);
            JdbcTemplate mysqlJdbc = context.getBean("mysqlJdbcTemplate", JdbcTemplate.class);
            JdbcTemplate pgJdbc = context.getBean("pgVectorJdbcTemplate", JdbcTemplate.class);

            assertSame(mysql, context.getBean(DataSource.class));
            assertSame(mysql, mysqlJdbc.getDataSource());
            assertSame(mysqlJdbc, context.getBean(JdbcTemplate.class));
            assertSame(mysqlJdbc, context.getBean("jdbcTemplate"));
            assertSame(postgres, pgJdbc.getDataSource());
            assertNotSame(mysqlJdbc, pgJdbc);
            assertEquals(2, context.getBeansOfType(JdbcTemplate.class).size());
            assertSame(mysqlJdbc, context.getBean(NamedParameterJdbcTemplate.class).getJdbcTemplate());

            SqlSessionFactory sqlSessionFactory = context.getBean(SqlSessionFactory.class);
            assertSame(mysql, sqlSessionFactory.getConfiguration().getEnvironment().getDataSource());
            assertSame(sqlSessionFactory, context.getBean(SqlSessionTemplate.class).getSqlSessionFactory());
            DataSourceTransactionManager transactions = context.getBean(DataSourceTransactionManager.class);
            assertSame("Workspace JDBC and MyBatis must join the same MySQL transaction", mysql,
                    transactions.getDataSource());

            assertPoolsHaveNotConnected(mysql, postgres);
        });
    }

    @Test
    public void actualBusinessConsumersUseMysqlWhileVectorConsumersKeepPostgres() {
        productionDataSources()
                .withInitializer(context -> {
                    ConfigurableListableBeanFactory beans = context.getBeanFactory();
                    // Register initialized mocks so their real classes' lifecycle methods never run.
                    beans.registerSingleton("armoryService", mock(IArmoryService.class));
                    beans.registerSingleton("mcpClientRegistry", mock(McpClientRegistry.class));
                    beans.registerSingleton("mcpToolCatalogService", mock(McpToolCatalogService.class));
                    beans.registerSingleton("llmObservationRecorder", mock(LlmObservationRecorder.class));
                    beans.registerSingleton("workspaceMcpPolicy", new WorkspaceMcpPolicy(""));
                    beans.registerSingleton("workspaceExecutionGuard", new WorkspaceExecutionGuard());
                    beans.registerSingleton("objectMapper", new ObjectMapper());
                    beans.registerSingleton("embeddingModel", mock(EmbeddingModel.class));
                    registerResourceCollaborators(beans, AgentRepository.class, AgentTaskJob.class);
                })
                .withUserConfiguration(WorkspaceAccessRepository.class, WorkspaceService.class,
                        WorkspaceDraftService.class, AdminConfigurationOwnership.class,
                        ObserveController.class, AgentRepository.class, AgentTaskJob.class,
                        SemanticCacheService.class, ToolVectorStore.class)
                .run(context -> {
                    assertNull("Real JDBC consumers must wire in the dual-database context", context.getStartupFailure());
                    JdbcTemplate mysql = context.getBean("mysqlJdbcTemplate", JdbcTemplate.class);
                    JdbcTemplate postgres = context.getBean("pgVectorJdbcTemplate", JdbcTemplate.class);
                    Map<Class<?>, String> businessFields = Map.of(
                            WorkspaceAccessRepository.class, "jdbc",
                            WorkspaceService.class, "db",
                            WorkspaceDraftService.class, "db",
                            AdminConfigurationOwnership.class, "jdbc",
                            ObserveController.class, "jdbcTemplate",
                            AgentRepository.class, "jdbcTemplate",
                            AgentTaskJob.class, "jdbcTemplate");
                    for (var consumer : businessFields.entrySet()) {
                        Object target = AopTestUtils.getUltimateTargetObject(context.getBean(consumer.getKey()));
                        assertSame(consumer.getKey().getSimpleName() + " must query MySQL", mysql,
                                ReflectionTestUtils.getField(target, consumer.getValue()));
                    }
                    assertSame(postgres, ReflectionTestUtils.getField(
                            context.getBean(SemanticCacheService.class), "pgVectorJdbcTemplate"));
                    assertSame(postgres, ReflectionTestUtils.getField(
                            context.getBean(ToolVectorStore.class), "pgVectorJdbcTemplate"));
                    assertPoolsHaveNotConnected(mysql.getDataSource(), postgres.getDataSource());
                });
    }

    private static void registerResourceCollaborators(ConfigurableListableBeanFactory beans, Class<?>... consumers) {
        for (Class<?> consumer : consumers) {
            for (Field field : consumer.getDeclaredFields()) {
                if (field.getType() == JdbcTemplate.class) continue;
                if (field.getAnnotation(jakarta.annotation.Resource.class) == null
                        && field.getAnnotation(javax.annotation.Resource.class) == null) continue;
                if (!beans.containsSingleton(field.getName())) {
                    beans.registerSingleton(field.getName(), mock(field.getType()));
                }
            }
        }
    }

    private static void assertPoolsHaveNotConnected(DataSource... sources) {
        for (DataSource source : sources) {
            assertNull("Wiring test must not create a physical DB connection",
                    ((HikariDataSource) source).getHikariPoolMXBean());
        }
    }
}
