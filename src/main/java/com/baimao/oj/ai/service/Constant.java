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
            "submission_analysis", "knowledge_retrieval", "testcase_generator"
    );

}
