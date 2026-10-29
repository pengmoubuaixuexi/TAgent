package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelCatalogRepository;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogEntry;
import cn.bugstack.ai.domain.agent.service.model.catalog.ModelCatalogPlanner;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthProbe;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRegistry;
import cn.bugstack.ai.trigger.workspace.ModelFailoverSettings;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual JDBC commit/rollback with a warmed request snapshot; no provider requests. */
public class ModelCatalogCommitRefreshTest {
    private static final ModelCatalogEntry ENTRY = new ModelCatalogEntry("alice", "new-api", "chat", "medium", 1,
            Set.of("TEXT"), true, true, false);

    @Test public void createdConnectionIsVisibleWhenItsTransactionReturns() {
        Fixture f = new Fixture();
        assertNull(f.planner.scopeOf("new-api")); // Warm the old snapshot without this connection.
        f.tx.executeWithoutResult(status -> {
            f.settings.register("alice", "new-api", "chat");
            assertNull("uncommitted connection must not be published", f.planner.scopeOf("new-api"));
        });
        assertEquals("no TTL wait after the create response", "alice", f.planner.scopeOf("new-api"));
        assertTrue(f.planner.entry(new ModelTarget("new-api", "chat")).isPresent());
    }

    @Test public void rolledBackConnectionNeverEntersTheSnapshot() {
        Fixture f = new Fixture();
        assertNull(f.planner.scopeOf("new-api"));
        f.tx.executeWithoutResult(status -> {
            f.settings.register("alice", "new-api", "chat");
            status.setRollbackOnly();
        });
        assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM visible_models", Integer.class).intValue());
        assertNull(f.planner.scopeOf("new-api"));
        assertFalse(f.planner.entry(new ModelTarget("new-api", "chat")).isPresent());
    }

    @Test public void manualCheckRefreshesAStaleSnapshotBeforeCallingTheProbe() {
        Fixture f = new Fixture();
        assertNull(f.planner.scopeOf("new-api"));
        f.db.update("INSERT INTO visible_models VALUES(1)"); // Another committed management operation.
        when(f.probe.check(any())).thenAnswer(invocation -> {
            assertEquals("alice", f.planner.scopeOf("new-api"));
            return new ModelHealthProbe.Result(new ModelTarget("new-api", "chat"), true, null);
        });
        assertEquals(true, f.settings.check("new-api", "chat", false).get("available"));
    }

    private static class Fixture {
        final JdbcTemplate db;
        final TransactionTemplate tx;
        final ModelCatalogPlanner planner;
        final ModelHealthProbe probe = mock(ModelHealthProbe.class);
        final ModelFailoverSettings settings;
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:catalog_commit_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("CREATE TABLE visible_models(id INT PRIMARY KEY)");
            tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            var repository = mock(IModelCatalogRepository.class);
            doAnswer(invocation -> { db.update("INSERT INTO visible_models VALUES(1)"); return null; }).when(repository).upsert(any());
            when(repository.usableEntries()).thenAnswer(invocation -> exists() ? List.of(ENTRY) : List.of());
            when(repository.connectionScopes()).thenAnswer(invocation -> exists() ? Map.of("new-api", "alice") : Map.of());
            planner = new ModelCatalogPlanner(repository);
            settings = new ModelFailoverSettings(repository, planner, new ModelHealthRegistry(java.util.Optional.empty()), probe);
        }
        boolean exists() { return db.queryForObject("SELECT COUNT(*) FROM visible_models", Integer.class) > 0; }
    }
}
