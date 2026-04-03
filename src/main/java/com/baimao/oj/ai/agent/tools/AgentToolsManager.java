package com.baimao.oj.ai.agent.tools;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.ai.mapper.AiToolCallLogMapper;
import com.baimao.oj.ai.mapper.AiToolConfigMapper;
import com.baimao.oj.ai.model.dto.AiChatSendRequest;
import com.baimao.oj.ai.model.entity.AiToolCallLog;
import com.baimao.oj.ai.model.entity.AiToolConfig;
import com.baimao.oj.ai.model.vo.AiToolEventVO;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.service.QuestionSubmitService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.baimao.oj.ai.service.Constant.EVENT_TOOL;
import static com.baimao.oj.ai.service.Constant.TOOL_ORDER;
import static com.baimao.oj.model.enums.JudgeInfoMessageEnum.ACCEPTED;

@Component
@Slf4j
/**
 * 工具管理器。
 * 由模型决定是否调用，同时继续保留项目自己的限流、事件推送和业务上下文。
 */
public class AgentToolsManager {

    /**
     * 透传给 Spring AI `toolContext` 的上下文键。
     */
    public static final String TOOL_RUNTIME_CONTEXT_KEY = "aiToolRuntimeContext";

    public record AgentToolDefinition(String name, String description, String inputSchema) {
    }

    private static final Map<String, AgentToolDefinition> AGENT_TOOL_DEFINITIONS = Map.of(
            "submission_analysis", new AgentToolDefinition(
                    "submission_analysis",
                    "Analyze the user's recent submissions for the current question and summarize pass rate, latest judge result, and execution metrics.",
                    "{\"limit\": optional integer, default 10}"
            ),
            "searchWeb", new AgentToolDefinition(
                    "searchWeb",
                    "Search public knowledge related to the current programming problem, algorithm, or failure pattern.",
                    "{\"query\": required string}"
            ),
            "testcase_generator", new AgentToolDefinition(
                    "testcase_generator",
                    "Generate boundary, tricky, and stress test ideas for the current programming problem.",
                    "{}"
            )
    );

    @Resource
    private AiToolConfigMapper aiToolConfigMapper;

    @Resource
    private AiToolCallLogMapper aiToolCallLogMapper;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Value("${search-api.api-key}")
    private String searchApiKey;

    /**
     * 获取当前启用的工具回调列表。
     * 数据库里禁用的工具不会暴露给模型。
     */
    public ToolCallback[] getEnabledToolCallbacks() {
        Map<String, AiToolConfig> configMap = listEnabledToolConfigMap();
        // ToolCallbacks.from(this) 会自动扫描当前类里所有 `@Tool` 注解的方法并生成回调列表，生成 ToolCallback[]
        return java.util.Arrays.stream(ToolCallbacks.from(this, new AgentTools(searchApiKey)))
                // 数据库管理哪些工具可用
                .filter(callback -> configMap.containsKey(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 最近提交分析工具。
     */
    public List<AgentToolDefinition> listEnabledTools() {
        Map<String, AiToolConfig> configMap = listEnabledToolConfigMap();
        List<AgentToolDefinition> definitions = new ArrayList<>();
        for (String toolName : TOOL_ORDER) {
            if (configMap.containsKey(toolName) && AGENT_TOOL_DEFINITIONS.containsKey(toolName)) {
                definitions.add(AGENT_TOOL_DEFINITIONS.get(toolName));
            }
        }
        return definitions;
    }

    /**
     * 当前要调用的工具是否支持
     * @param toolName
     * @return
     */
    public boolean supportsTool(String toolName) {
        String normalizedToolName = normalizeToolName(toolName);
        return listEnabledTools().stream()
                .anyMatch(tool -> tool.name().equalsIgnoreCase(normalizedToolName));
    }

    public String executeTool(String toolName, Map<String, Object> arguments, RuntimeContext runtimeContext) {
        String normalizedToolName = normalizeToolName(toolName);
        if (StringUtils.isBlank(normalizedToolName)) {
            return recordEvent(runtimeContext, "unknown_tool", "error", "Tool name is blank.");
        }
        switch (normalizedToolName) {
            case "submission_analysis":
                long limit = resolveLong(arguments, "limit", 10L);
                return executeTool(normalizedToolName, runtimeContext,
                        context -> toolSubmissionAnalysis(context.userId(), context.question().getId(),
                                context.contestId(), limit));
            case "searchWeb":
                if (StringUtils.isBlank(searchApiKey)) {
                    return recordEvent(runtimeContext, normalizedToolName, "skipped",
                            "Search API key is not configured.");
                }
                String query = resolveString(arguments, "query",
                        runtimeContext.requestBody() == null ? null : runtimeContext.requestBody().getMessage());
                if (StringUtils.isBlank(query)) {
                    return recordEvent(runtimeContext, normalizedToolName, "skipped", "Search query is empty.");
                }
                return executeTool(normalizedToolName, runtimeContext,
                        context -> new AgentTools(searchApiKey).searchWeb(query));
            case "testcase_generator":
                return executeTool(normalizedToolName, runtimeContext,
                        context -> toolGenerateTestCases(context.question()));
            default:
                return recordEvent(runtimeContext, normalizedToolName, "error",
                        "Unknown tool: " + normalizedToolName);
        }
    }

    @Tool(name = "submission_analysis", description = "Analyze the user's recent submissions for the current question and summarize pass rate and latest judge result.")
    public String submissionAnalysis(ToolContext toolContext) {
        return executeTool("submission_analysis", toolContext,
                context -> toolSubmissionAnalysis(context.userId(), context.question().getId(), context.contestId(), 10L));
    }

    /**
     * 测试用例建议工具。
     */
    @Tool(name = "testcase_generator", description = "Generate test ideas for the current programming problem.")
    public String testcaseGenerator(ToolContext toolContext) {
        return executeTool("testcase_generator", toolContext,
                context -> toolGenerateTestCases(context.question()));
    }

    /**
     * 工具执行总入口。
     * 统一处理开关、限流、异常日志和事件回传。
     */
    private String executeTool(String toolName, ToolContext toolContext, ToolExecutor executor) {
        RuntimeContext runtimeContext = getRuntimeContext(toolContext);
        return executeTool(toolName, runtimeContext, executor);
    }

    private String executeTool(String toolName, RuntimeContext runtimeContext, ToolExecutor executor) {
        AiToolConfig toolConfig = resolveToolConfig(toolName);
        if (toolConfig == null || !Objects.equals(toolConfig.getEnabled(), 1)) {
            return recordEvent(runtimeContext, toolName, "skipped", "Tool is disabled.");
        }
        int dailyLimit = toolConfig.getDailyLimit() == null ? 30 : toolConfig.getDailyLimit();
        if (!checkAndRecordToolCall(runtimeContext.userId(), toolName, dailyLimit)) {
            return recordEvent(runtimeContext, toolName, "skipped", "Daily tool call limit exceeded.");
        }
        try {
            String summary = executor.execute(runtimeContext);
            log.info("执行工具: {}， 执行结果: {}", toolName, summary);
            return recordEvent(runtimeContext, toolName, "done", summary);
        } catch (Exception e) {
            log.error("tool {} execute error", toolName, e);
            return recordEvent(runtimeContext, toolName, "error", "Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * 分析用户最近的判题记录，给模型提供更具体的辅导上下文。
     */
    public String toolSubmissionAnalysis(Long userId, Long questionId, Long contestId, Long num) {
        LambdaQueryWrapper<QuestionSubmit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QuestionSubmit::getUserId, userId);
        queryWrapper.eq(QuestionSubmit::getQuestionId, questionId);
        if (contestId != null && contestId > 0) {
            queryWrapper.eq(QuestionSubmit::getContestId, contestId);
        }
        // 按提交时间降序
        queryWrapper.orderByDesc(QuestionSubmit::getCreateTime);
        queryWrapper.last("limit " + (num > 0 ? num : 10));
        List<QuestionSubmit> submits = questionSubmitService.list(queryWrapper);
        if (CollUtil.isEmpty(submits)) {
            return "No related submission history was found.";
        }
        long acceptedCount = 0;
        String latestJudgeMsg = "N/A";
        Long latestTime = null;
        for (QuestionSubmit submit : submits) {
            JudgeInfo judgeInfo = parseJudgeInfo(submit.getJudgeInfo());
            if (judgeInfo == null) {
                continue;
            }
            if (ACCEPTED.getText().equalsIgnoreCase(judgeInfo.getMessage())) {
                acceptedCount++;
            }
            if ("N/A".equals(latestJudgeMsg)) {
                latestJudgeMsg = StringUtils.defaultString(judgeInfo.getMessage(), "N/A");
                latestTime = judgeInfo.getTime();
            }
        }
        return String.format("共 %d 次提交: [通过 %d 次, 最近一次提交的结果: %s, 执行时间: %s ms]。",
                submits.size(), acceptedCount, latestJudgeMsg, latestTime == null ? "N/A" : latestTime);
    }

    /**
     * 生成基础、边界和压力场景的测试“建议”。
     */
    public String toolGenerateTestCases(Question question) {
        if (question == null) {
            return "测试用例建议：\n1. 基础正确性样例。\n2. 最小/最大边界样例。\n3. 大规模压力样例。";
        }

        String title = StringUtils.defaultString(question.getTitle());
        String content = StringUtils.defaultString(question.getContent());
        List<String> tags = extractTags(question.getTags());
        String topicText = (title + "\n" + content + "\n" + String.join(" ", tags)).toLowerCase();

        Set<String> boundaryCases = new LinkedHashSet<>();
        Set<String> trickyCases = new LinkedHashSet<>();
        Set<String> stressCases = new LinkedHashSet<>();

        boundaryCases.add("最小合法输入（若允许则包含 size=0 或 1），重点验证初始化与下标处理。");
        boundaryCases.add("最大合法值域输入，重点检查容易溢出的算术运算。");

        if (containsAny(topicText, "array", "数组", "two pointers", "双指针", "sliding window", "窗口")) {
            boundaryCases.add("数组边界：空数组、单元素、全相等、严格递增/递减。");
            trickyCases.add("指针移动陷阱：大量重复值导致漏算或重复计数。");
        }
        if (containsAny(topicText, "string", "字符串", "substring", "子串")) {
            boundaryCases.add("字符串边界：空串、单字符、全相同字符、交替字符。");
            trickyCases.add("大小写与重复模式输入，验证窗口/子串边界是否正确。");
        }
        if (containsAny(topicText, "binary", "二分", "sorted", "有序")) {
            boundaryCases.add("二分边界：目标在首尾位置、目标不存在、存在重复元素。");
            trickyCases.add("检查 mid 更新规则，避免在双元素区间出现死循环。");
        }
        if (containsAny(topicText, "dp", "dynamic programming", "动态规划")) {
            boundaryCases.add("动态规划边界：仅基础状态与首次转移。");
            trickyCases.add("验证不可达状态与转移顺序，避免复用过期状态。");
        }
        if (containsAny(topicText, "graph", "图", "tree", "树", "bfs", "dfs")) {
            boundaryCases.add("图结构边界：单节点、非连通分量、可选情况下的环/自环。");
            trickyCases.add("检查 visited 重置与多连通分量遍历正确性。");
        }
        if (containsAny(topicText, "greedy", "贪心")) {
            trickyCases.add("构造反例，验证局部最优是否会破坏全局最优。");
        }
        if (containsAny(topicText, "mod", "取模", "1e9+7", "1000000007")) {
            trickyCases.add("使用大中间值，验证取模时机与溢出处理。");
        }

        if (StringUtils.isNotBlank(question.getJudgeCase())) {
            boundaryCases.add("先回放官方样例输入，确认解析与输出格式兼容。");
        }

        if (containsAny(topicText, "10^5", "10^6", "10^7", "100000", "1000000")) {
            stressCases.add("接近约束上限的压力输入（n 接近最大值），重点观察时间复杂度。");
        }
        stressCases.add("随机中等规模批量数据，用于发现不稳定分支与隐藏组合边界。");
        stressCases.add("对抗性数据分布（全相等/严格单调/频次高度偏斜）。");

        List<String> lines = new ArrayList<>();
        lines.add("题目测试建议：" + StringUtils.defaultIfBlank(title, "未命名题目"));
        if (CollUtil.isNotEmpty(tags)) {
            lines.add("题目标签：" + String.join("，", tags));
        }

        lines.add("1）边界场景：");
        int idx = 1;
        for (String item : boundaryCases) {
            lines.add("- " + (idx++) + ". " + item);
        }

        lines.add("2）易错正确性场景：");
        idx = 1;
        for (String item : trickyCases) {
            lines.add("- " + (idx++) + ". " + item);
        }

        lines.add("3）压力场景：");
        idx = 1;
        for (String item : stressCases) {
            lines.add("- " + (idx++) + ". " + item);
        }
        return String.join("\n", lines);
    }

    private static List<String> extractTags(String tagsJson) {
        if (StringUtils.isBlank(tagsJson)) {
            return new ArrayList<>();
        }
        String[] rawTags = tagsJson.trim().split(",");
        if (rawTags.length == 0) {
            return new ArrayList<>();
        }

        Set<String> normalizedTags = new LinkedHashSet<>();
        for (String rawTag : rawTags) {
            String tag = StringUtils.trimToEmpty(rawTag);
            if (StringUtils.isNotBlank(tag)) {
                normalizedTags.add(tag);
            }
        }
        return new ArrayList<>(normalizedTags);
    }

    private static boolean containsAny(String source, String... keywords) {
        if (StringUtils.isBlank(source) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.isNotBlank(keyword) && source.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeToolName(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return null;
        }
        return toolName.trim();
    }

    private String resolveString(Map<String, Object> arguments, String key, String defaultValue) {
        if (arguments == null || arguments.isEmpty()) {
            return defaultValue;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.defaultIfBlank(text, defaultValue);
    }

    private long resolveLong(Map<String, Object> arguments, String key, long defaultValue) {
        if (arguments == null || arguments.isEmpty()) {
            return defaultValue;
        }
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 解析判题结果 JSON。
     */
    private JudgeInfo parseJudgeInfo(String judgeInfoJson) {
        if (StringUtils.isBlank(judgeInfoJson)) {
            return null;
        }
        try {
            return JSONUtil.toBean(judgeInfoJson, JudgeInfo.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 Spring AI 的 `ToolContext` 中取回项目侧运行时上下文。
     */
    private RuntimeContext getRuntimeContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("Missing tool runtime context");
        }
        Object runtimeContext = toolContext.getContext().get(TOOL_RUNTIME_CONTEXT_KEY);
        if (runtimeContext instanceof RuntimeContext context) {
            return context;
        }
        throw new IllegalStateException("Invalid tool runtime context");
    }

    /**
     * 记录工具执行结果，并在流式场景下向前端推送 `tool` 事件。
     */
    private String recordEvent(RuntimeContext runtimeContext, String toolName, String status, String summary) {
        AiToolEventVO eventVO = new AiToolEventVO(toolName, status, summary);
        runtimeContext.toolEvents().add(eventVO);
        if (runtimeContext.emitter() != null) {
            sendEvent(runtimeContext.emitter(), EVENT_TOOL, eventVO);
        }
        return summary;
    }

    /**
     * 查询当前启用的工具配置；如果数据库没有配置，则使用默认兜底配置。
     */
    private Map<String, AiToolConfig> listEnabledToolConfigMap() {
        LambdaQueryWrapper<AiToolConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiToolConfig::getEnabled, 1);
        List<AiToolConfig> dbList = aiToolConfigMapper.selectList(queryWrapper);
        Map<String, AiToolConfig> map = new HashMap<>();
        if (CollUtil.isNotEmpty(dbList)) {
            for (AiToolConfig config : dbList) {
                map.put(config.getToolName(), config);
            }
            return map;
        }
        for (String tool : TOOL_ORDER) {
            AiToolConfig config = new AiToolConfig();
            config.setToolName(tool);
            config.setEnabled(1);
            config.setDailyLimit(30);
            map.put(tool, config);
        }
        return map;
    }

    /**
     * 查询单个工具的启用配置。
     */
    private AiToolConfig resolveToolConfig(String toolName) {
        return listEnabledToolConfigMap().get(toolName);
    }

    /**
     * 按“用户 + 工具 + 日期”维度做每日调用次数限制。
     */
    private boolean checkAndRecordToolCall(Long userId, String toolName, Integer dailyLimit) {
        String today = DateUtil.formatDate(new java.util.Date());
        LambdaQueryWrapper<AiToolCallLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiToolCallLog::getUserId, userId);
        queryWrapper.eq(AiToolCallLog::getToolName, toolName);
        queryWrapper.eq(AiToolCallLog::getCallDate, today);
        queryWrapper.last("limit 1");
        AiToolCallLog logRow = aiToolCallLogMapper.selectOne(queryWrapper);
        if (logRow == null) {
            logRow = new AiToolCallLog();
            logRow.setUserId(userId);
            logRow.setToolName(toolName);
            logRow.setCallDate(today);
            logRow.setCallCount(1);
            aiToolCallLogMapper.insert(logRow);
            return true;
        }
        if (logRow.getCallCount() >= dailyLimit) {
            return false;
        }
        logRow.setCallCount(logRow.getCallCount() + 1);
        aiToolCallLogMapper.updateById(logRow);
        return true;
    }


    /**
     * 发送工具执行事件。
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(JSONUtil.toJsonStr(data)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 工具运行时上下文。
     * 这里聚合了工具执行时会用到的用户、题目、比赛、请求体和 SSE 句柄。
     */
    public record RuntimeContext(Long userId, Question question, Long contestId,
                                 AiChatSendRequest requestBody, SseEmitter emitter,
                                 List<AiToolEventVO> toolEvents) {
    }
}
