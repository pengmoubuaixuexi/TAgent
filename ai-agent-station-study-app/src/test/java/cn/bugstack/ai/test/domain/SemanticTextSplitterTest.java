package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.rag.splitter.SemanticTextSplitter;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SemanticTextSplitter 轻量单测：覆盖 child 级小块复用时最容易踩的边界。
 */
public class SemanticTextSplitterTest {

    @Test
    public void childSizedSplitterKeepsCodeFenceAtomic() {
        SemanticTextSplitter splitter = new SemanticTextSplitter(120, 30, 30, 1000);
        String text = """
                # 示例

                这是一段普通介绍，用来把正文和代码块分开。这里继续补充一些文字，让切分器需要产生多个小块。

                ```java
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                ```

                代码块后面还有一段解释，说明这个 Demo 如何运行，以及为什么不能把代码围栏拆到不同 chunk 里。
                """;

        List<String> chunks = splitter.splitTextToChunks(text);

        assertTrue("应切出多个 child-sized chunks", chunks.size() > 1);
        assertTrue("某个 chunk 应完整包含 java 代码块",
                chunks.stream().anyMatch(c -> c.contains("```java")
                        && c.contains("System.out.println")
                        && c.contains("```")));
        assertFalse("不能出现只含代码正文但没有围栏的半截代码 chunk",
                chunks.stream().anyMatch(c -> c.contains("System.out.println")
                        && (!c.contains("```java") || !c.contains("```"))));
    }

    @Test
    public void childSizedSplitterPrefersSentenceBoundary() {
        SemanticTextSplitter splitter = new SemanticTextSplitter(45, 8, 10, 90);
        String text = """
                第一句用于测试边界。第二句应该尽量保持完整。第三句也应该保持完整。第四句继续补充内容。
                第五句用于拉长文本。第六句继续制造多个分片。第七句验证不会从句子中间断开。第八句作为结尾。
                """;

        List<String> chunks = splitter.splitTextToChunks(text);

        assertTrue("应切出至少两个 chunk", chunks.size() >= 2);
        assertTrue("关键句子应完整保留在某个 chunk 中",
                chunks.stream().anyMatch(c -> c.contains("第二句应该尽量保持完整。")));
        assertTrue("后续关键句子也应完整保留在某个 chunk 中",
                chunks.stream().anyMatch(c -> c.contains("第七句验证不会从句子中间断开。")));
    }

    @Test
    public void englishSentencesKeepNumbersAndSpacesIntact() {
        SemanticTextSplitter splitter = new SemanticTextSplitter(60, 0, 10, 200);
        String text = "Accuracy improves by 3.5 points on ImageNet. See Fig.3 for details. "
                + "The backbone is ResNet-50 with 25.6M parameters. Training takes 90 epochs on 8 GPUs.";

        List<String> chunks = splitter.splitTextToChunks(text);

        assertTrue("应按句子切出多个 chunk", chunks.size() >= 2);
        assertTrue("小数不能被切开", chunks.stream().anyMatch(c -> c.contains("3.5 points")));
        assertTrue("Fig.3 不是句末", chunks.stream().anyMatch(c -> c.contains("Fig.3 for details.")));
        assertTrue("同一 chunk 内英文句间空格不能丢", chunks.stream().noneMatch(c -> c.matches("(?s).*\\.[A-Z].*")));
        assertTrue(chunks.stream().anyMatch(c -> c.contains("25.6M parameters.")));
    }
}
