package com.baimao.oj.service.impl;

import com.baimao.oj.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.vo.QuestionSubmitVO;
import com.baimao.oj.service.QuestionSubmitService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class QuestionSubmitServiceImplTest {

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Test
    void doQuestionSubmit() {
        QuestionSubmitAddRequest q = new QuestionSubmitAddRequest();
        q.setQuestionId(1L);
        q.setCode("aa");
        q.setLanguage("java");
        User loginUser = new User();
        loginUser.setId(1L);

        QuestionSubmitVO questionSubmitVO = questionSubmitService.doQuestionSubmitVO(q, loginUser);
        Assertions.assertNotNull(questionSubmitVO);

    }

    @Test
    void doQuestionSubmitVO() {
    }
}
