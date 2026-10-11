package cn.bugstack.ai.infrastructure.adapter.repository;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.*;
import com.alibaba.fastjson.JSON;
import org.junit.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in loopback Redis; synthetic per-test keys only, never flushes a database. */
public class RedisToolEvidenceIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private RedisRunSnapshotService service;
    private String run,prefix;
    @Before public void setup() {
        String port=System.getenv("TAGENT_TEST_REDIS_PORT");assumeTrue(port!=null);
        connection=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(port));connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
        run=UUID.randomUUID().toString();prefix="tagent:v2:test:";
        service=RedisToolEvidenceAccessTest.service(redis);ReflectionTestUtils.setField(service,"keyPrefix",prefix);
        redis.opsForValue().set(prefix+run,JSON.toJSONString(RunSnapshot.builder().runId(run).userId("alice").build()),java.time.Duration.ofMinutes(1));
    }
    @After public void cleanup() {
        if(redis!=null) redis.delete(List.of(prefix+run,prefix+"tool-evidence:"+run,prefix+"tool-evidence-size:"+run));
        if(connection!=null) connection.destroy();
    }
    private ToolEvidenceRecord evidence(String id,String output) { return ToolEvidenceRecord.builder().evidenceId("tool:"+id).output(output).build(); }
    @Test public void concurrentWritesCannotExceedAtomicEntryCeiling() throws Exception {
        ReflectionTestUtils.setField(service,"maxToolEvidencePerRun",2);
        var workers=Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<20;i++) {String id=""+i;results.add(workers.submit(()->service.saveToolEvidence(run,"alice",evidence(id,"北京"))));}
            int accepted=0;for(var result:results) if(result.get()) accepted++;
            assertEquals(2,accepted);assertEquals(Long.valueOf(2),redis.opsForHash().size(prefix+"tool-evidence:"+run));
        } finally {workers.shutdownNow();}
    }
    @Test public void wrongOwnerAndExpiredAnchorCannotReadOrWrite() {
        assertFalse(service.saveToolEvidence(run,"bob",evidence("one","private")));
        assertTrue(service.saveToolEvidence(run,"alice",evidence("one","private")));
        assertTrue(service.findToolEvidence(run,"bob","tool:one").isEmpty());
        assertEquals("private",service.findToolEvidence(run,"alice","tool:one").orElseThrow().getOutput());
        redis.delete(prefix+run);
        assertTrue(service.findToolEvidence(run,"alice","tool:one").isEmpty());
        assertFalse(service.saveToolEvidence(run,"alice",evidence("two","private")));
    }
    @Test public void serializedByteLimitAndReplacementAccountingAreAtomic() {
        ReflectionTestUtils.setField(service,"maxToolEvidenceBytesPerRun",1024);
        assertTrue(service.saveToolEvidence(run,"alice",evidence("one","中".repeat(250))));
        assertFalse(service.saveToolEvidence(run,"alice",evidence("two","中".repeat(250))));
        assertTrue(service.saveToolEvidence(run,"alice",evidence("one","small")));
        assertTrue(service.saveToolEvidence(run,"alice",evidence("two","中".repeat(250))));
        assertEquals("small",service.findToolEvidence(run,"alice","tool:one").orElseThrow().getOutput());
    }
}
