package cn.bugstack.ai.test.security;

import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Objects;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real mapper against an explicitly isolated loopback database, synthetic account only. */
public class AccountPasswordMySqlIntegrationTest {
    @Test public void passwordCompareAndSetIsByteExactEvenWithCaseInsensitiveDatabaseCollation() throws Exception {
        String url = System.getenv("TAGENT_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank());
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/tagent_workspace_isolation_test(?:\\?.*)?"));
        var source = new DriverManagerDataSource(url, Objects.requireNonNullElse(System.getenv("TAGENT_TEST_DB_USER"), "root"),
                Objects.requireNonNullElse(System.getenv("TAGENT_TEST_DB_PASSWORD"), ""));
        try (var connection = source.getConnection()) { assertEquals("tagent_workspace_isolation_test", connection.getCatalog()); }
        var db = new JdbcTemplate(source);
        String owner = "password_test_" + UUID.randomUUID().toString().substring(0, 8);
        db.update("INSERT INTO admin_user(user_id,username,password,status,role) VALUES(?,?,?,1,'USER')", owner, owner, "LegacyCaseSensitive");
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mybatis/mapper/admin_user_mapper.xml"));
            try (var session = factory.getObject().openSession(true)) {
                IAdminUserDao mapper = session.getMapper(IAdminUserDao.class);
                assertEquals(0, mapper.updatePasswordIfCurrent(owner, "legacycasesensitive", "incorrect-overwrite"));
                assertEquals(1, mapper.updatePasswordIfCurrent(owner, "LegacyCaseSensitive", "new-synthetic-hash"));
                assertEquals(0, mapper.updatePasswordIfCurrent(owner, "LegacyCaseSensitive", "stale-overwrite"));
                assertEquals("new-synthetic-hash", mapper.queryByUserId(owner).getPassword());
            }
        } finally { db.update("DELETE FROM admin_user WHERE user_id=?", owner); }
    }
}
