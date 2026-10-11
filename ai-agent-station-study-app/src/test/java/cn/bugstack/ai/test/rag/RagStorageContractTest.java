package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import com.alibaba.fastjson.JSON;
import org.apache.http.ProtocolVersion;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** No Elasticsearch instance: exercises real request building and response validation. */
public class RagStorageContractTest {
    private RestClient client;
    private BM25SearchService service;

    @Before public void setup() {
        client = mock(RestClient.class);
        service = new BM25SearchService();
        ReflectionTestUtils.setField(service, "restClient", client);
        ReflectionTestUtils.setField(service, "ragIndex", "rag-test");
    }

    @Test public void http200WithFailedBulkItemIsNotReportedAsSuccess() throws Exception {
        for (String body : List.of(
                "{\"errors\":true,\"items\":[{\"index\":{\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\"}}}]}",
                "{\"errors\":false,\"items\":[{\"index\":{\"status\":429}}]}",
                "{\"errors\":false,\"items\":[]}", "{}")) {
            doReturn(response(body)).when(client).performRequest(any());
            assertThrows(RagStorageException.class, () -> service.index(List.of(document())));
        }
    }

    @Test public void transportFailurePropagatesAndDoesNotLeakPayload() throws Exception {
        when(client.performRequest(any())).thenThrow(new IOException("SYNTHETIC_PRIVATE_BACKEND_DETAIL"));
        RagStorageException error = assertThrows(RagStorageException.class, () -> service.index(List.of(document())));
        assertFalse(error.getMessage().contains("SYNTHETIC_PRIVATE"));
        assertNotNull(error.getCause());
    }

    @Test public void completeBulkWritesStableIdentityAndOwner() throws Exception {
        doReturn(response("{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}")).when(client).performRequest(any());
        service.index(List.of(document()));
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(client).performRequest(captor.capture());
        String[] lines = body(captor.getValue()).split("\n");
        var source = JSON.parseObject(lines[1]);
        assertEquals("alice", source.getString("user_id"));
        assertEquals("rf_fixture", source.getString("rag_file_id"));
        assertEquals("rf_fixture-p0", source.getString("parent_id"));
    }

    @Test public void deletionIsOwnerScopedAndRejectsPartialBackendCleanup() throws Exception {
        var scope = new RagFileScope("rf_fixture", "alice", "kb", "same.txt", false);
        doReturn(response("{\"deleted\":2,\"timed_out\":false,\"version_conflicts\":0,\"failures\":[]}")).when(client).performRequest(any());
        service.deleteFile(scope);
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(client).performRequest(captor.capture());
        String query = body(captor.getValue());
        assertTrue(query.contains("\"user_id\":\"alice\""));
        assertTrue(query.contains("\"knowledge\":\"kb\""));
        assertTrue(query.contains("rf_fixture"));
        assertFalse("New files must not be deleted by a shared filename", query.contains("same.txt"));
        for (String failure : List.of(
                "{\"deleted\":1,\"timed_out\":true,\"failures\":[]}",
                "{\"deleted\":1,\"version_conflicts\":1,\"failures\":[]}",
                "{\"deleted\":1,\"failures\":[{\"cause\":\"failure\"}]}", "{}")) {
            doReturn(response(failure)).when(client).performRequest(any());
            assertThrows(RagStorageException.class, () -> service.deleteFile(scope));
        }
    }

    @Test public void legacyDeletionNeverUsesAnalyzedFilenameOrDeletesNewFormatDocuments() throws Exception {
        doReturn(
                response("{\"rag-test\":{\"mappings\":{\"source\":{\"mapping\":{\"source\":{\"type\":\"text\"}}},\"source.keyword\":{\"mapping\":{\"keyword\":{\"type\":\"keyword\",\"ignore_above\":256}}}}}}"),
                response("{\"deleted\":1,\"timed_out\":false,\"failures\":[]}")).when(client).performRequest(any());
        service.deleteFile(new RagFileScope("123-0", "alice", "kb", "same.txt", true));
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(client, times(2)).performRequest(captor.capture());
        String query = body(captor.getAllValues().get(1));
        assertTrue(query.contains("\"source.keyword\":\"same.txt\""));
        assertTrue(query.contains("must_not"));
        assertTrue(query.contains("rag_file_id"));
        assertFalse(query.contains("\"source\":\"same.txt\""));

        reset(client);
        doReturn(response("{\"rag-test\":{\"mappings\":{\"source\":{\"mapping\":{\"source\":{\"type\":\"text\"}}}}}}")).when(client).performRequest(any());
        assertThrows(RagStorageException.class,
                () -> service.deleteFile(new RagFileScope("123-0", "alice", "kb", "same.txt", true)));
        verify(client, times(1)).performRequest(any()); // field inspection only, no deletion
    }

    @Test public void legacyKeywordNormalizationAndTruncatedNamesCannotSilentlyDeleteOnlyTheDirectory() throws Exception {
        for (String attributes : List.of(
                "\"type\":\"keyword\",\"ignore_above\":3",
                "\"type\":\"keyword\",\"normalizer\":\"lowercase\"")) {
            reset(client);
            doReturn(response("{\"rag-test\":{\"mappings\":{\"source.keyword\":{\"mapping\":{\"keyword\":{" + attributes + "}}}}}}"))
                    .when(client).performRequest(any());
            assertThrows(RagStorageException.class,
                    () -> service.deleteFile(new RagFileScope("123-0", "alice", "kb", "same.txt", true)));
            verify(client, times(1)).performRequest(any());
        }
    }

    @Test public void keywordSearchAppliesTheSamePublicationGateAsVectorSearch() throws Exception {
        var visibility = mock(cn.bugstack.ai.domain.agent.service.rag.RagDocumentVisibility.class);
        ReflectionTestUtils.setField(service, "visibility", visibility);
        when(visibility.published(any())).thenReturn(List.of());
        doAnswer(call -> {
            org.elasticsearch.client.ResponseListener listener = call.getArgument(1);
            listener.onSuccess(response("{\"timed_out\":false,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[{\"_id\":\"id\",\"_score\":1,\"_source\":{\"content\":\"partial secret\",\"rag_file_id\":\"rf_fixture\",\"knowledge\":\"kb\",\"user_id\":\"alice\",\"source\":\"file.txt\"}}]}}"));
            return mock(org.elasticsearch.client.Cancellable.class);
        }).when(client).performRequestAsync(any(), any());
        assertTrue(service.search("question", "kb", Map.of("user_id", "alice"), 3).isEmpty());
        verify(visibility).published(argThat(docs -> docs.size() == 1 && "rf_fixture".equals(docs.get(0).getMetadata().get("rag_file_id"))));
        doThrow(new IllegalStateException("publication database unavailable")).when(visibility).published(any());
        assertThrows(IllegalStateException.class, () -> service.search("question", "kb", Map.of("user_id", "alice"), 3));
    }

    private Document document() {
        return new Document("id-a", "fixture", Map.of("user_id", "alice", "knowledge", "kb",
                "source", "file.txt", "rag_file_id", "rf_fixture", "parent_id", "rf_fixture-p0"));
    }
    private Response response(String json) {
        Response response = mock(Response.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "OK"));
        when(response.getEntity()).thenReturn(new StringEntity(json, StandardCharsets.UTF_8));
        return response;
    }
    private String body(Request request) throws IOException {
        try (var stream = request.getEntity().getContent()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
