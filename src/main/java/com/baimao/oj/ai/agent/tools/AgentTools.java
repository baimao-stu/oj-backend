package com.baimao.oj.ai.agent.tools;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.dto.question.JudgeCase;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.service.QuestionSubmitService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.baimao.oj.model.enums.JudgeInfoMessageEnum.ACCEPTED;

/**
 * 工具定义与实现。
 */
@Component
@Slf4j
public class AgentTools {

    private final String SEARCH_API_APIKEY;
    private final QuestionSubmitService questionSubmitService;

    public AgentTools(@Value("${search-api.api-key}") String apiKey,
                      QuestionSubmitService questionSubmitService) {
        this.SEARCH_API_APIKEY = apiKey;
        this.questionSubmitService = questionSubmitService;
    }

    /**
     * 网络搜索工具
     * @param query 搜索关键词
     * @param toolContext SpringAI 管理的工具上下文
     * @return
     */
    @Tool(name = "searchWeb",
            description = "Search public knowledge related to the current programming problem, algorithm, or failure pattern.")
    public String searchWeb(@ToolParam(description = "Search query keyword") String query,
                            ToolContext toolContext) {
        AgentToolsManager.RuntimeContext runtimeContext = getRuntimeContext(toolContext);
        if (StringUtils.isBlank(SEARCH_API_APIKEY)) {
            return "Search API key is not configured.";
        }
        String resolvedQuery = StringUtils.defaultIfBlank(StringUtils.trimToNull(query),
                runtimeContext.requestBody() == null ? null : runtimeContext.requestBody().getMessage());
        if (StringUtils.isBlank(resolvedQuery)) {
            return "Search query is empty.";
        }
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", resolvedQuery);
        paramMap.put("api_key", SEARCH_API_APIKEY);
        paramMap.put("engine", "baidu");
        try {
            String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
            // 取出返回结果的前 5 条
            JSONObject jsonObject = JSONUtil.parseObj(response);
            // 提取 organic_results 部分
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            List<Object> objects = organicResults.subList(0, 5);
            // 拼接搜索结果为字符串
            String result = objects.stream().map(obj -> {
                JSONObject tmpJSONObject = (JSONObject) obj;
                return tmpJSONObject.toString();
            }).collect(Collectors.joining(","));
            return result;
        } catch (Exception e) {
            log.info("Error searching Baidu: " + e.getMessage());
            return "Error searching Baidu: " + e.getMessage();
        }
    }

    /**
     * 分析用户最近的判题记录，给模型提供更具体的辅导上下文。
     */
    @Tool(name = "submission_analysis",
            description = "Analyze the user's recent submissions for the current question and summarize pass rate, latest judge result, and execution metrics.")
    public String submissionAnalysis(
            @ToolParam(description = "Maximum number of recent submissions to analyze", required = false) Long limit,
            ToolContext toolContext) {
        AgentToolsManager.RuntimeContext runtimeContext = getRuntimeContext(toolContext);
        Long questionId = runtimeContext.question() == null ? null : runtimeContext.question().getId();
        if (runtimeContext.userId() == null || questionId == null) {
            return "Submission analysis context is unavailable.";
        }
        long num = limit == null ? 10L : limit;
        if (questionSubmitService == null) {
            return "Submission analysis service is unavailable.";
        }
        LambdaQueryWrapper<QuestionSubmit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QuestionSubmit::getUserId, runtimeContext.userId());
        queryWrapper.eq(QuestionSubmit::getQuestionId, questionId);
        if (runtimeContext.contestId() != null && runtimeContext.contestId() > 0) {
            queryWrapper.eq(QuestionSubmit::getContestId, runtimeContext.contestId());
        }
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
    @Tool(name = "testcase_generator",
            description = "Generate boundary, tricky, and stress test ideas for the current programming problem.")
    public String generateTestCases(ToolContext toolContext) {
        AgentToolsManager.RuntimeContext runtimeContext = getRuntimeContext(toolContext);
        Question question = runtimeContext.question();
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

    /**
     * 分析当前样例 / 最近判题报错的可能原因。
     */
    @Tool(name = "sample_error_analyzer",
            description = "Analyze the current sample or latest judge error and explain likely root causes with actionable checks.")
    public String analyzeSampleError(
            @ToolParam(description = "Optional raw error text from user", required = false) String errorText,
            ToolContext toolContext) {
        AgentToolsManager.RuntimeContext runtimeContext = getRuntimeContext(toolContext);
        Question question = runtimeContext.question();
        String latestJudgeResult = runtimeContext.requestBody() == null ? null : runtimeContext.requestBody().getLatestJudgeResult();
        String resolvedErrorText = StringUtils.defaultIfBlank(StringUtils.trimToNull(errorText),
                StringUtils.trimToNull(latestJudgeResult));
        if (StringUtils.isBlank(resolvedErrorText)) {
            return "当前没有检测到明确报错信息。请补充 latestJudgeResult 或粘贴报错原文（编译器/运行时日志），我会继续定位。";
        }

        String category = detectErrorCategory(resolvedErrorText);
        List<String> reasons = buildReasonHints(category, resolvedErrorText);
        List<String> sampleHints = buildSampleHints(question);

        List<String> lines = new ArrayList<>();
        lines.add("报错类型判断：" + category);
        lines.add("判题/报错信息：" + resolvedErrorText);
        lines.add("可能原因：");
        for (String reason : reasons) {
            lines.add("- " + reason);
        }
        if (CollUtil.isNotEmpty(sampleHints)) {
            lines.add("样例对照建议：");
            for (String sampleHint : sampleHints) {
                lines.add("- " + sampleHint);
            }
        }
        lines.add("下一步：先用题目样例逐条本地跑并打印中间变量，确认是输入解析、核心逻辑还是边界处理出错。");
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

    private static String detectErrorCategory(String errorText) {
        String lower = StringUtils.defaultString(errorText).toLowerCase();
        if (containsAny(lower, "compile", "compilation", "syntax", "编译", "语法", "cannot find symbol")) {
            return "编译错误";
        }
        if (containsAny(lower, "runtime error", "exception", "nullpointer", "数组越界", "空指针", "运行时")) {
            return "运行时错误";
        }
        if (containsAny(lower, "wrong answer", "wa", "答案错误", "expected", "输出不一致")) {
            return "答案错误";
        }
        if (containsAny(lower, "time limit", "tle", "超时")) {
            return "超时";
        }
        if (containsAny(lower, "memory limit", "mle", "内存超限")) {
            return "内存超限";
        }
        return "未知类型错误";
    }

    private static List<String> buildReasonHints(String category, String errorText) {
        List<String> hints = new ArrayList<>();
        switch (category) {
            case "编译错误" -> {
                hints.add("变量名/方法名拼写与声明不一致，或缺少必要导包。" );
                hints.add("语法结构不完整（括号、分号、泛型尖括号）导致编译器提前中断。" );
                hints.add("函数签名与题目要求不一致（返回值、参数数量、类名/主函数）。" );
            }
            case "运行时错误" -> {
                hints.add("边界检查不足：空数组、空字符串、下标越界、除零等。" );
                hints.add("空引用访问（如集合/对象未初始化）引发异常。" );
                hints.add("递归终止条件缺失或栈深过大导致栈溢出。" );
            }
            case "答案错误" -> {
                hints.add("对题意理解偏差：返回值含义、输出格式或顺序不符合判题要求。" );
                hints.add("边界条件遗漏（最小值、最大值、重复元素、负数等）。" );
                hints.add("状态更新顺序或循环范围存在 off-by-one 问题。" );
            }
            case "超时" -> {
                hints.add("算法复杂度过高，可能需要从 O(n^2) 优化到 O(n log n)/O(n)。" );
                hints.add("循环中重复计算可预处理的信息，缺少剪枝或缓存。" );
                hints.add("I/O 处理较慢，批量读取与输出可减少额外开销。" );
            }
            case "内存超限" -> {
                hints.add("保存了不必要的中间状态或复制了大数组/字符串。" );
                hints.add("DP 维度过高，可尝试滚动数组或状态压缩。" );
                hints.add("递归深度过深且每层占用较大栈帧。" );
            }
            default -> {
                hints.add("报错文本未包含典型关键词，请提供完整报错栈和触发输入。" );
                hints.add("先对照题目样例逐行比对输入解析与输出格式。" );
            }
        }

        if (StringUtils.containsIgnoreCase(errorText, "expected")) {
            hints.add("日志中出现 expected 字样，通常表示输出与标准答案在某处开始分叉。" );
        }
        if (StringUtils.containsAnyIgnoreCase(errorText, "null", "nullpointer")) {
            hints.add("日志中出现 null 相关关键词，优先检查对象/集合初始化路径。" );
        }
        return hints;
    }

    private List<String> buildSampleHints(Question question) {
        if (question == null || StringUtils.isBlank(question.getJudgeCase())) {
            return new ArrayList<>();
        }
        try {
            JSONArray array = JSONUtil.parseArray(question.getJudgeCase());
            List<JudgeCase> judgeCases = JSONUtil.toList(array, JudgeCase.class);
            if (CollUtil.isEmpty(judgeCases)) {
                return new ArrayList<>();
            }
            List<String> hints = new ArrayList<>();
            int max = Math.min(2, judgeCases.size());
            for (int i = 0; i < max; i++) {
                JudgeCase judgeCase = judgeCases.get(i);
                hints.add(String.format("样例 %d 输入：%s", i + 1,
                        StringUtils.defaultIfBlank(StringUtils.trimToNull(judgeCase.getInput()), "(空输入)")));
                hints.add(String.format("样例 %d 期望输出：%s", i + 1,
                        StringUtils.defaultIfBlank(StringUtils.trimToNull(judgeCase.getOutput()), "(空输出)")));
            }
            hints.add("重点核对：空格/换行格式、数字类型范围、是否多输出调试信息。" );
            return hints;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
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

    private AgentToolsManager.RuntimeContext getRuntimeContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("Missing tool runtime context");
        }
        Object runtimeContext = toolContext.getContext().get(AgentToolsManager.TOOL_RUNTIME_CONTEXT_KEY);
        if (runtimeContext instanceof AgentToolsManager.RuntimeContext context) {
            return context;
        }
        throw new IllegalStateException("Invalid tool runtime context");
    }

}
