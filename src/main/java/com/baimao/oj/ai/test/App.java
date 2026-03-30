package com.baimao.oj.ai.test;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
public class App {

    private final ChatClient chatClient;

    public App(DashScopeChatModel dashscopeChatModel) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    public String chat(String question) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(question)
                .call().chatResponse();
        String answer = chatResponse.getResult().getOutput().getText();
        return answer;
    }

}
