package cn.bugstack.ai.domain.agent.service.memory.conflict;

import java.util.List;

/**
 * P2.1 10.3 记忆冲突解决器。
 * <p>
 * 写入新记忆前，检索同 topic 的已有记忆，判断是覆盖、合并还是保留两份。
 */
public interface IMemoryConflictResolver {

    enum Decision { OVERWRITE, MERGE, KEEP_BOTH, SKIP }

    /**
     * 解析冲突。
     * @param topic     记忆主题
     * @param newContent 新记忆内容
     * @param existingContents 已有记忆内容列表（由近到远）
     * @return 处理决策
     */
    Decision resolve(String topic, String newContent, List<String> existingContents);
}
