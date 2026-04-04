package com.baimao.oj.ai.agent.tools;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
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
