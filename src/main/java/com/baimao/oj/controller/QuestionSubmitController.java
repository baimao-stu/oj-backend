package com.baimao.oj.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baimao.oj.common.BaseResponse;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.common.ResultUtils;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.baimao.oj.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.vo.QuestionSubmitVO;
import com.baimao.oj.service.QuestionSubmitService;
import com.baimao.oj.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 题目提交接口
 */
//@RestController
//@RequestMapping("/question_submit")
@Deprecated
@Slf4j
public class QuestionSubmitController {

//    @Resource
//    private QuestionSubmitService questionSubmitService;
//
//    @Resource
//    private UserService userService;
//
//    /**
//     * 题目提交
//     * @param questionSubmitAddRequest
//     * @param request
//     * @return 提交记录的 id
//     */
//    @PostMapping("/")
//    public BaseResponse<Long> doQuestionSubmit(@RequestBody QuestionSubmitAddRequest questionSubmitAddRequest,
//            HttpServletRequest request) {
//        if (questionSubmitAddRequest == null || questionSubmitAddRequest.getQuestionId() <= 0) {
//            throw new BusinessException(ErrorCode.PARAMS_ERROR);
//        }
//        // 登录才能提交，获取当前已登录的用户
//        final User loginUser = userService.getLoginUser(request);
//        Long questionSubmitId = questionSubmitService.doQuestionSubmit(questionSubmitAddRequest, loginUser);
//        return ResultUtils.success(questionSubmitId);
//    }
//
//    /**
//     * 分页获取问题提交列表（除了管理员，只有用户自己能看到详细代码）
//     * @param questionSubmitQueryRequest
//     * @return
//     */
//    @PostMapping("/list/page")
//    public BaseResponse<Page<QuestionSubmitVO>> listQuestionSubmitByPage(
//            @RequestBody QuestionSubmitQueryRequest questionSubmitQueryRequest,HttpServletRequest request) {
//        long current = questionSubmitQueryRequest.getCurrent();
//        long size = questionSubmitQueryRequest.getPageSize();
//        //1. 查询到的是从数据库查到的题目提交列表，要做脱敏返回VO
//        Page<QuestionSubmit> questionSubmitPage = questionSubmitService.page(new Page<>(current, size),
//                questionSubmitService.getQueryWrapper(questionSubmitQueryRequest));
//        //2. 将查到的题目提交列表脱敏为VO
//        final User loginUser = userService.getLoginUser(request);
//        Page<QuestionSubmitVO> questionSubmitVOPage = questionSubmitService.getQuestionSubmitVOPage(questionSubmitPage, loginUser);
//        return ResultUtils.success(questionSubmitVOPage);
//    }

}
