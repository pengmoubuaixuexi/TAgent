package cn.bugstack.ai.domain.agent.service.armory.node.factory.element;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Chain-of-Verification（CoVe）事实核查 advisor（P2.2 11.1）。
 * <p>
 * after() 阶段从助理回答中提取事实声明，逐条检索 RAG 库验证。
 * 未验证通过的声明写入 log（ELK 可见）+ metric。
 * <p>
 * 当前版本只打观测（Light CoVe），不修改下游 response。
 * 完整 NLI 版（LLM 做自然语言推理判断冲突）留 P3。
 */
@Slf4j
public class CoVeAdvisor implements BaseAdvisor {

    private final VectorStore vectorStore;
    private final ChatClient factExtractor;
    private final int topK;
    private final int order;

    public CoVeAdvisor(VectorStore vectorStore, ChatClient factExtractor, int topK) {
        this(vectorStore, factExtractor, topK, 200);
    }

    public CoVeAdvisor(VectorStore vectorStore, ChatClient factExtractor, int topK, int order) {
        this.vectorStore = vectorStore;
        this.factExtractor = factExtractor;
        this.topK = topK > 0 ? topK : 3;
        this.order = order;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (vectorStore == null || factExtractor == null) return response;

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) return response;
        AssistantMessage assistant = chatResponse.getResult().getOutput();
        if (assistant == null) return response;
        String answerText = assistant.getText();
        if (answerText == null || answerText.isBlank() || answerText.length() < 50) return response;

        List<String> claims;
        try {
            claims = extractClaims(answerText);
        } catch (Exception e) {
            log.debug("cove extractClaims failed: {}", e.getMessage());
            return response;
        }
        if (claims.isEmpty()) return response;

        int verified = 0, unverified = 0;
        List<String> unresolved = new ArrayList<>();
        for (String claim : claims) {
            try {
                if (verifyClaim(claim)) {
                    verified++;
                } else {
                    unverified++;
                    unresolved.add(claim);
                }
            } catch (Exception e) {
                unverified++;
            }
        }

        if (!unresolved.isEmpty()) {
            log.warn("[CoVe] {}/{} claims unverified; unverified=[{}]",
                    unverified, claims.size(), String.join(" | ", unresolved));
        } else {
            log.info("[CoVe] {}/{} claims verified OK", verified, claims.size());
        }

        return response;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return after(chain.nextCall(request), chain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return BaseAdvisor.super.adviseStream(request, chain);
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    private List<String> extractClaims(String answerText) {
        String prompt = """
                Extract factual claims from the following answer. A factual claim is a statement
                that can be verified against a knowledge base. Skip opinions, suggestions, and
                conversational phrases. Output each claim on a new line, prefixed with "- ".

                Answer:
                %s

                Claims:""";
        String limited = answerText.length() > 3000 ? answerText.substring(0, 3000) : answerText;
        String result = factExtractor.prompt(new Prompt(String.format(prompt, limited)))
                .call().content();
        if (result == null || result.isBlank()) return List.of();

        List<String> claims = new ArrayList<>();
        for (String line : result.split("\n")) {
            line = line.trim();
            if (line.startsWith("- ")) line = line.substring(2).trim();
            if (line.length() > 10 && line.length() < 500) {
                claims.add(line);
            }
        }
        return claims;
    }

    private boolean verifyClaim(String claim) {
        SearchRequest req = SearchRequest.builder()
                .query(claim)
                .topK(topK)
                .build();
        List<Document> docs = vectorStore.similaritySearch(req);
        if (docs == null || docs.isEmpty()) return false;

        for (Document d : docs) {
            if (d.getScore() != null && d.getScore() > 0.7) {
                return true;
            }
        }
        return false;
    }
}
