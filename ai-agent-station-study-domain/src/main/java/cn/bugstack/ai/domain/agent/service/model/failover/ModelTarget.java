package cn.bugstack.ai.domain.agent.service.model.failover;

/** One upstream choice: a server-assembled connection plus the model name sent in the request body. */
public record ModelTarget(String apiId, String model) {
    public ModelTarget {
        if (apiId == null || apiId.isBlank() || model == null || model.isBlank())
            throw new IllegalArgumentException("模型连接或模型名缺失");
    }

    @Override public String toString() { return model + "@" + apiId; }
}
