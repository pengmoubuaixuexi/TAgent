package cn.bugstack.ai.test.memory;

import cn.bugstack.ai.infrastructure.adapter.repository.SummarizingChatMemory;
import cn.bugstack.ai.infrastructure.adapter.repository.cache.MemoryCacheService;
import cn.bugstack.ai.infrastructure.dao.IAiChatMemorySummaryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemorySummary;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * chat-memory floor + equality bug（v3.md §1.8 用户提案 / Codex §7.6 / CC §689 定稿）：
 * <ul>
 *   <li><b>equality bug</b>：{@code SummarizingChatMemory.get()} 旧实现 {@code watermark>0 && watermark<raw.size() ? subList : raw}，
 *       在 watermark==raw.size()(全摘要,如 40/40) 或 watermark>raw.size()(异常) 时错误返回全量 raw。
 *       修复为 {@code start=max(0,min(watermark,size))}，全摘要/越界 → 空未摘要窗口。</li>
 *   <li><b>floor</b>：仅摘要路径(watermark>0)，未摘要窗口塌缩到 &lt; floor(2 轮=4 条，且 ≤ maxMessages) 时，
 *       按「完整轮」从 raw 末尾兜底，起点对齐到 USER 消息，不切断配对。普通滑窗(watermark==0)不动。</li>
 * </ul>
 * 纯单元：mock repository/cache，不连真实 DB/LLM。
 */
public class SummarizingChatMemoryFloorTest {

    private SummarizingChatMemory memory;
    private ChatMemoryRepository chatRepo;
    private MemoryCacheService cache;

    private static final String C = "conv-1";

    @Before
    public void setup() {
        memory = new SummarizingChatMemory();
        chatRepo = mock(ChatMemoryRepository.class);
        cache = mock(MemoryCacheService.class);
        IAiChatMemorySummaryDao dao = mock(IAiChatMemorySummaryDao.class);
        ReflectionTestUtils.setField(memory, "chatMemoryRepository", chatRepo);
        ReflectionTestUtils.setField(memory, "memoryCache", cache);
        ReflectionTestUtils.setField(memory, "summaryDao", dao);
        ReflectionTestUtils.setField(memory, "maxMessages", 20);
    }

    /** 交替 USER/ASSISTANT：index 偶=USER(轮起点)，奇=ASSISTANT。 */
    private static List<Message> raw(int n) {
        List<Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(i % 2 == 0 ? new UserMessage("u" + i) : new AssistantMessage("a" + i));
        }
        return list;
    }

    private void stub(int rawSize, int watermark, String summaryText) {
        when(chatRepo.findByConversationId(C)).thenReturn(raw(rawSize));
        when(cache.getSummary(C)).thenReturn(
                AiChatMemorySummary.builder().conversationId(C).summaryMsgCount(watermark).summary(summaryText).build());
    }

    /** 极端不规整历史：很早一个 USER 后面跟一长串 ASSISTANT，模拟工具/模型多轮尾巴没有用户边界。 */
    private static List<Message> longAssistantTail(int assistantCount) {
        List<Message> list = new ArrayList<>();
        list.add(new UserMessage("old-user"));
        for (int i = 0; i < assistantCount; i++) {
            list.add(new AssistantMessage("assistant-tail-" + i));
        }
        return list;
    }

    /** 40/40 全摘要：旧 bug 返回全量 40；修复后 → 空未摘要窗口 → floor 兜底末尾完整轮(4 条，首条 USER)。 */
    @Test
    public void fullySummarized_40_40_floorsInsteadOfReturningAll() {
        stub(40, 40, null);
        List<Message> got = memory.get(C);
        assertEquals("全摘要不再返回全量，floor 兜底 4 条", 4, got.size());
        assertEquals("兜底窗口首条对齐 USER 轮起点", MessageType.USER, got.get(0).getMessageType());
    }

    /** watermark>raw（异常）：start=min 收敛到 size，不越界 → 空窗口 → floor 兜底。 */
    @Test
    public void watermarkExceedsRaw_failsClosedThenFloors() {
        stub(40, 50, null);
        List<Message> got = memory.get(C);
        assertEquals(4, got.size());
        assertEquals(MessageType.USER, got.get(0).getMessageType());
    }

    /** 41/40：未摘要窗口=1(<floor) → floor 兜底；n-floor 落 ASSISTANT 时向前对齐到 USER（返回 ≥floor 且首 USER）。 */
    @Test
    public void partialSummarized_41_40_floorsAndAlignsToUser() {
        stub(41, 40, null);
        List<Message> got = memory.get(C);
        assertTrue("至少 floor 条", got.size() >= 4);
        assertEquals("起点对齐 USER，不从 ASSISTANT 中间切入", MessageType.USER, got.get(0).getMessageType());
    }

    /** 39/30：未摘要窗口=9(≥floor) → 正常窗口，不兜底，原样 9 条。 */
    @Test
    public void partialSummarized_39_30_normalWindowNoFloor() {
        stub(39, 30, null);
        List<Message> got = memory.get(C);
        assertEquals("9 条未摘要、≥floor，不兜底", 9, got.size());
    }

    /** 无摘要(watermark==0)：普通滑窗路径不受 floor 影响，返回末尾 maxMessages 条。 */
    @Test
    public void noSummary_watermarkZero_plainSlidingWindowUnchanged() {
        when(chatRepo.findByConversationId(C)).thenReturn(raw(25));
        when(cache.getSummary(C)).thenReturn(null);
        List<Message> got = memory.get(C);
        assertEquals("普通滑窗 = maxMessages(20)，floor 不介入", 20, got.size());
    }

    /** floor 受 maxMessages 上限约束：maxMessages=2 → floor=min(4,2)=2。 */
    @Test
    public void floorCappedByMaxMessages() {
        ReflectionTestUtils.setField(memory, "maxMessages", 2);
        stub(40, 40, null);
        List<Message> got = memory.get(C);
        assertEquals("floor 不超过 maxMessages", 2, got.size());
        assertEquals(MessageType.USER, got.get(0).getMessageType());
    }

    /**
     * 长 assistant/tool 尾巴里如果最近 maxMessages 范围内没有 USER 边界，floor 不应为了对齐一路扫回很早的 USER，
     * 否则会把大量已摘要历史重新塞回模型窗口。此时退化为 floor 起点，优先守住预算。
     */
    @Test
    public void longAssistantTailWithoutRecentUser_doesNotExceedBudgetToAlignOldUser() {
        List<Message> raw = longAssistantTail(30);
        when(chatRepo.findByConversationId(C)).thenReturn(raw);
        when(cache.getSummary(C)).thenReturn(
                AiChatMemorySummary.builder().conversationId(C).summaryMsgCount(raw.size()).summary(null).build());

        List<Message> got = memory.get(C);

        assertEquals("最近 maxMessages 内无 USER 边界时，退化为 floor 起点，不扫回旧 USER 导致窗口爆炸", 4, got.size());
        assertEquals("退化窗口可能从 ASSISTANT 开始，但不会超过预算", MessageType.ASSISTANT, got.get(0).getMessageType());
    }

    /** 摘要文本存在时，floor 窗口前仍拼接 SystemMessage 摘要前缀。 */
    @Test
    public void summaryPrefixPrependedBeforeFloorWindow() {
        stub(40, 40, "背景摘要XYZ");
        List<Message> got = memory.get(C);
        assertEquals("1 条摘要前缀 + 4 条 floor 兜底", 5, got.size());
        assertEquals(MessageType.SYSTEM, got.get(0).getMessageType());
        assertTrue(((SystemMessage) got.get(0)).getText().contains("背景摘要XYZ"));
        assertEquals("摘要后第一条对齐 USER", MessageType.USER, got.get(1).getMessageType());
    }
}
