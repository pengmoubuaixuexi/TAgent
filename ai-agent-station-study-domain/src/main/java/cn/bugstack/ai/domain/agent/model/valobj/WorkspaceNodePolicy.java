package cn.bugstack.ai.domain.agent.model.valobj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import java.util.*;

/** Server-authored, immutable ceiling on a private node's business capabilities. */
public record WorkspaceNodePolicy(String agentId, String clientId, String taskPrompt,
                                  Map<String, Set<String>> tools, Set<String> allTools, Set<String> publicMcpIds,
                                  Set<String> onDemandMcpIds) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public WorkspaceNodePolicy {
        Map<String,Set<String>> copy=new LinkedHashMap<>();
        tools.forEach((id,names)->copy.put(id,Set.copyOf(names)));
        tools=Collections.unmodifiableMap(copy); allTools=Set.copyOf(allTools);
        publicMcpIds=Set.copyOf(publicMcpIds);
        onDemandMcpIds=Set.copyOf(onDemandMcpIds);
    }
    public WorkspaceNodePolicy(String agentId,String clientId,String taskPrompt,Map<String,Set<String>> tools,Set<String> allTools,Set<String> publicMcpIds) {
        this(agentId,clientId,taskPrompt,tools,allTools,publicMcpIds,Set.of());
    }
    public WorkspaceNodePolicy(String agentId,String clientId,String taskPrompt,Map<String,Set<String>> tools,Set<String> allTools) {
        this(agentId,clientId,taskPrompt,tools,allTools,Set.of());
    }
    public static WorkspaceNodePolicy fromCapabilities(String capabilities) {
        if (capabilities==null || capabilities.isBlank()) return null;
        try {
            var root=JSON.readTree(capabilities);
            if (root==null || !root.has("workspaceNode")) return null;
            var node=root.path("workspaceNode");
            if (node.path("clientId").asText().isBlank() || node.path("agentId").asText().isBlank()
                    || !node.path("mcpBindings").isArray()) throw new IllegalArgumentException();
            Map<String,Set<String>> tools=new LinkedHashMap<>(); Set<String> all=new HashSet<>(), onDemand=new HashSet<>();
            for (var binding:node.path("mcpBindings")) {
                String id=binding.path("mcpId").asText(); Set<String> names=new HashSet<>();
                for (var name:binding.path("toolNames")) names.add(name.asText());
                tools.put(id,names); if (binding.path("allTools").asBoolean(false)) all.add(id);
                if ("ON_DEMAND".equals(binding.path("loadMode").asText())) onDemand.add(id);
            }
            Set<String> publicIds=new HashSet<>();node.path("publicMcpIds").forEach(id->publicIds.add(id.asText()));
            return new WorkspaceNodePolicy(node.path("agentId").asText(),node.path("clientId").asText(),
                    node.path("taskPrompt").asText(""),tools,all,publicIds,onDemand);
        } catch (Exception e) { throw new IllegalStateException("Invalid workspace node policy",e); }
    }
    public boolean eager(String mcpId) { return !onDemandMcpIds.contains(mcpId); }
    public boolean allows(String mcpId,String name) {
        // An explicit selection narrows even a connection in the public pool.
        return tools.containsKey(mcpId) ? allTools.contains(mcpId) || tools.get(mcpId).contains(name) : publicMcpIds.contains(mcpId);
    }
    public boolean allows(ToolCallback callback) {
        return callback instanceof MeteredToolCallback metered && callback.getToolDefinition()!=null
                && allows(metered.getMcpId(),callback.getToolDefinition().name());
    }
    public List<ToolCallback> filter(List<ToolCallback> callbacks) {
        return callbacks==null ? List.of() : callbacks.stream().filter(this::allows).toList();
    }
}
