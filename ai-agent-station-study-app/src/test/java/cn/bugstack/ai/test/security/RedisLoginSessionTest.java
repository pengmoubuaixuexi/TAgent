package cn.bugstack.ai.test.security;

import org.junit.Test;
import org.junit.Assume;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Duration;
import java.util.List;
import static org.junit.Assert.*;

public class RedisLoginSessionTest {
    @Test @SuppressWarnings({"unchecked", "rawtypes"}) public void redisPersistsRoleAndSecurityContextAndDeletesSession() {
        Assume.assumeTrue(Boolean.getBoolean("tagent.test.redis"));
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 16379);
        connection.afterPropertiesSet(); connection.start();
        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(connection);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JdkSerializationRedisSerializer());
        template.setHashValueSerializer(new JdkSerializationRedisSerializer());
        template.afterPropertiesSet();
        var redisRepository = new RedisSessionRepository(template);
        redisRepository.setRedisKeyNamespace("tagent:login-test");
        redisRepository.setDefaultMaxInactiveInterval(Duration.ofHours(8));
        org.springframework.session.SessionRepository<org.springframework.session.Session> repository =
                (org.springframework.session.SessionRepository) redisRepository;
        var session = repository.createSession();
        try {
            session.setAttribute("userId", "10001"); session.setAttribute("role", "ADMIN");
            session.setAttribute("isAdmin", true);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("10001", null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            session.setAttribute("SPRING_SECURITY_CONTEXT", context);
            repository.save(session);
            var restored = repository.findById(session.getId());
            assertNotNull(restored);
            assertEquals("10001", restored.getAttribute("userId"));
            assertEquals("ADMIN", restored.getAttribute("role"));
            assertEquals(Boolean.TRUE, restored.getAttribute("isAdmin"));
            org.springframework.security.core.context.SecurityContext saved = restored.getAttribute("SPRING_SECURITY_CONTEXT");
            assertEquals("ROLE_ADMIN", saved.getAuthentication().getAuthorities().iterator().next().getAuthority());
            assertTrue(template.getExpire("tagent:login-test:sessions:" + session.getId()) > 0);
            repository.deleteById(session.getId());
            assertNull(repository.findById(session.getId()));
        } finally { repository.deleteById(session.getId()); connection.destroy(); }
    }
}
