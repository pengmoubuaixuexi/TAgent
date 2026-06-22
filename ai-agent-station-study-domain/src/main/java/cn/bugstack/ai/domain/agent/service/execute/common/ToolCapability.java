package cn.bugstack.ai.domain.agent.service.execute.common;

/**
 * 单次 LLM 调用可获准的工具能力类别。
 * <p>这是 policy ceiling，不代表请求一定实际带有对应工具；实际可见性还要与运行期开关、额度和工具装配取交集。</p>
 */
public enum ToolCapability {
    BUSINESS_TOOL,
    ASK_USER,
    REQUEST_TOOL
}
