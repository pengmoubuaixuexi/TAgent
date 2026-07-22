package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.flow.step.FlowStepOutputContract;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlowStepOutputContractTest {

    @Test
    public void codeStepRequiresCompleteCodeInsteadOfMetadata() {
        String step = "第3步：生成Python画图代码\n"
                + "- **预期输出**: 完整的Python代码，包含数据定义、绘图逻辑和保存代码\n"
                + "- **成功标准**: 代码语法正确，可运行";

        String contract = FlowStepOutputContract.render(step);

        assertTrue(contract.contains("计划规定的预期输出：完整的Python代码"));
        assertTrue(contract.contains("必须包含从 import/声明到最后一行的完整可运行代码"));
        assertTrue(contract.contains("不能只传 file_created、路径、函数清单或图表配置"));
    }

    @Test
    public void filePersistenceStepDoesNotPretendPathIsSourceCode() {
        String step = "第4步：保存代码\n"
                + "- **预期输出**: Python文件成功创建于目标路径\n"
                + "- **成功标准**: 文件写入成功，内容完整无误";

        String contract = FlowStepOutputContract.render(step);

        assertTrue(contract.contains("本步骤是数据/结果交付"));
        assertFalse(contract.contains("本步骤是代码交付"));
    }

    @Test
    public void missingExpectedOutputFallsBackToGoalDeliverable() {
        String contract = FlowStepOutputContract.render("- **目标能力**: 提取完整实验数据");

        assertTrue(contract.contains("以【步骤内容】中的目标能力和执行方法"));
        assertTrue(contract.contains("执行报告、文件名、路径、数量、类型、图表清单和摘要元数据都不能替代交付物"));
    }
}
