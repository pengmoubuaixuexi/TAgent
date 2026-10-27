package cn.bugstack.ai.domain.agent.service.sandbox;

import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Sandboxed code execution (run_python). Implemented by the trigger layer, which talks to the
 * sandbox runner service and saves produced files as the user's artifacts.
 */
public interface ISandboxService {

    String TOOL_NAME = "run_python";

    ToolDefinition DEFINITION = ToolDefinition.builder()
            .name(TOOL_NAME)
            .description("在隔离的沙箱里运行 Python 3.11 代码，用于数据分析、精确计算、画图和生成文件。"
                    + "沙箱不能联网，也不能安装新包；已装 pandas、numpy、scipy、matplotlib（已配置中文字体）、seaborn、"
                    + "openpyxl、XlsxWriter、python-docx、reportlab、tabulate。"
                    + "用户附件用 inputFiles 传入，会放在只读目录 /input（文件名与附件名相同）。"
                    + "要交给用户的文件写到 /output，运行结束后自动保存为用户的产物（图片、CSV、Excel、Word、PDF 等）；"
                    + "中间文件写在当前目录，本次任务后续的运行还能读到。单次最多 60 秒、内存 512MB。"
                    + "用 print 输出需要的结果，返回里有标准输出、错误信息和保存的文件。不要用它做不需要计算的事。")
            .inputSchema("""
                    {"type":"object","additionalProperties":false,"required":["code"],"properties":{
                      "code":{"type":"string","minLength":1,"maxLength":100000,"description":"完整的 Python 代码，直接写源码，不要加 Markdown 代码围栏"},
                      "inputFiles":{"type":"array","maxItems":10,"items":{"type":"string"},"description":"要放到 /input 的附件 attachmentId，见附件清单"},
                      "purpose":{"type":"string","maxLength":100,"description":"一句话说明这次运行做什么，会显示给用户"}}}
                    """)
            .build();

    /** Live status of one execution, reported while it waits or runs. */
    interface Progress {
        void queued(int ahead);

        void running(long elapsedMs);
    }

    boolean available();

    /** Executes one run_python call. The scope is the request's trusted ToolContext. Returns a JSON result for the model. */
    String run(String toolCallId, String arguments, Map<String, Object> trustedScope, Progress progress);

    /** Deletes the run's shared working directory in the sandbox. Best effort. */
    void releaseRun(String runId);

    static boolean isName(String name) {
        return name != null && TOOL_NAME.equalsIgnoreCase(name);
    }
}
