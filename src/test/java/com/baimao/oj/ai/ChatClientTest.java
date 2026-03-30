package com.baimao.oj.ai;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.baimao.oj.ai.test.App;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@SpringBootTest
public class ChatClientTest {

    @Resource
    private App app;

    @Test
    public void chat() {
        String question = "你好";
        String answer = app.chat(question);
        Assertions.assertNotNull(answer);
    }

}
