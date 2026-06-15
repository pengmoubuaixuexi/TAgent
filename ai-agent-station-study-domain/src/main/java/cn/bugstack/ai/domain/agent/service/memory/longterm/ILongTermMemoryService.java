package cn.bugstack.ai.domain.agent.service.memory.longterm;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 用户级语义记忆服务（P1.7）。
 * <p>
 * 跟 ChatMemory 的区别：ChatMemory 装的是"本次对话的消息流"（加 saveAll/findByConversationId），
 * Long-Term Memory 装的是"用户级跨会话事实/偏好"（语义检索 + 衰减 + 归档）。
 * <p>
 * 当前 MVP 仅落 save + retrieveTopK；后续 P2 接入：
 * <ul>
 *   <li>自动事实抽取：advisor.after() 走小模型把对话里的"事实声明"扒出来 save</li>
 *   <li>记忆冲突解决：save 前先 retrieve 同 topic 旧记忆，LLM 判断是覆盖还是合并</li>
 *   <li>遗忘机制：access_count + last_accessed 双因子打分定期归档</li>
 * </ul>
 */
public interface ILongTermMemoryService {

    /**
     * 写入一条新记忆。
     * @param userId   用户维度（暂用 sessionId 代理）
     * @param content  事实陈述文本（被 embed）
     * @param topic    主题分类（preference / fact / decision / skill / null）
     * @param source   来源（auto / manual）
     * @param sessionId 来源对话 sessionId（可空）
     * @return 新记忆的 memoryId（UUID）
     */
    String save(String userId, String content, String topic, String source, String sessionId);

    /**
     * 按用户 + query 召回 top-K 相关记忆。
     * 命中即触发 access 计数 + 时间戳更新（衰减算法用）。
     * @return 命中的 Document（含 metadata.memory_id/user_id/topic 等），按相关度倒序
     */
    List<Document> retrieveTopK(String userId, String query, int topK);

    /** 标记一条记忆为已归档（不出现在 retrieveTopK 结果里） */
    void archive(String memoryId);

    /**
     * 获取用户画像记忆（偏好/事实/技能），不受语义相似度限制，
     * 始终在 advisor before() 里拼入上下文，避免"写了 Python 偏好但下次问二分查找时命中不了"。
     * @return 记忆内容列表，按 last_accessed 降序
     */
    List<String> retrieveProfile(String userId);

    /**
     * 混合检索：核心记忆（高频+近期） + 语义相关记忆（向量相似度），去重后返回。
     * <p>
     * 相比 {@link #retrieveProfile(String)} 直接 dump 全部记忆，本方法用
     * query 做相关性过滤，同时保留高频核心记忆确保关键信息不丢失。
     * @param userId    用户维度
     * @param query     当前用户输入，用于向量相似度检索
     * @param coreN     核心记忆保留条数（按复合分数排序）
     * @param relevantK 语义相关记忆保留条数
     * @return 记忆内容列表，去重后总计 ≤ coreN + relevantK
     */
    List<String> retrieveForInjection(String userId, String query, int coreN, int relevantK);

    /**
     * P2.1 10.2 遗忘衰减（2026-06-07 重设计）：把闲置超过 "基线天数 + 热度宽限" 的冷记忆批量归档
     * （破坏性：删向量 + archived=1）。
     * <p>
     * 归档阈值 = baseDays + k(topic) × min(access_count, cap)：access_count 经 touchAccess 的 1 天
     * 节流后≈"被召回的不同天数"，越热宽限越久；k 按 topic 分档（技能/偏好耐久 &gt; 计划/情况会过期）；
     * cap 给宽限一个有限上界，取代旧的 access_count&lt;3 硬阈值（破阈即"永久免疫"）。画像槽位永不归档。
     * 基线天数、各档 k、cap 均走配置（{@code agent.memory.long-term.decay-*}）。
     * @param limit 单次最多归档条数
     * @return 实际归档条数
     */
    int runDecay(int limit);
}
