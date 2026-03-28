package com.baimao.oj.service;

import com.baimao.oj.model.entity.QuestionSubmit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 用户服务测试

 */
@SpringBootTest
public class ContestTest {

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Test
    void testQuestionSubmitByCIdAndUId() {
        List<QuestionSubmit> questionSubmitPageByCIdAndUId = questionSubmitService.getQuestionSubmitPageByCIdAndUId(2l, 1l);
        System.out.println(questionSubmitPageByCIdAndUId);
    }
}
