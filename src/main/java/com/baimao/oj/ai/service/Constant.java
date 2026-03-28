package com.baimao.oj.ai.service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public interface Constant {
    String ROLE_USER = "user";
    String ROLE_ASSISTANT = "assistant";
    String EVENT_META = "meta";
    String EVENT_TOOL = "tool";
    String EVENT_DELTA = "delta";
    String EVENT_DONE = "done";
    String EVENT_ERROR = "error";
    List<String> TOOL_ORDER = Arrays.asList(
            "submission_analysis", "knowledge_retrieval", "testcase_generator", "sandbox_execute"
    );
    Pattern MAIN_CODE_PATTERN = Pattern.compile(
            "(public\\s+class\\s+\\w+)|(public\\s+static\\s+void\\s+main\\s*\\()|(def\\s+\\w+\\s*\\()|(#include\\s*<)",
            Pattern.CASE_INSENSITIVE
    );

    String DEFAULT_NORMAL_SYSTEM_PROMPT = """
            You are ACoder, an all-capable AI assistant, aimed at solving program task presented by the user.
            For any out-of-scope inquiries, you must use this fixed reply: "您的问题与编程无关，请重新提问。".
            """;
    String DEFAULT_AGENT_SYSTEM_PROMPT = DEFAULT_NORMAL_SYSTEM_PROMPT + """
            You have various tools at your disposal that you can call upon to efficiently complete complex requests.
            Whether it's programming, information retrieval, file processing, web browsing,
            or human interaction (only for extreme cases), you can handle it all.
            If you don't need to use any tools, you can directly respond to the user.
            """;

}
