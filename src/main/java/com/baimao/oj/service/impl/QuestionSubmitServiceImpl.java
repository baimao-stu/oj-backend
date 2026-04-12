package com.baimao.oj.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baimao.oj.judge.JudgeService;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.dto.question.JudgeCase;
import com.baimao.oj.model.enums.JudgeInfoMessageEnum;
import com.baimao.oj.model.vo.QuestionVO;
import com.baimao.oj.model.vo.UserVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.constant.CommonConstant;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.mapper.QuestionSubmitMapper;
import com.baimao.oj.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.baimao.oj.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.baimao.oj.model.entity.Question;
import com.baimao.oj.model.entity.QuestionSubmit;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.enums.QuestionSubmitLanguageEnum;
import com.baimao.oj.model.enums.QuestionSubmitStatusEnum;
import com.baimao.oj.model.vo.QuestionSubmitVO;
import com.baimao.oj.service.ContestRankService;
import com.baimao.oj.service.QuestionService;
import com.baimao.oj.service.QuestionSubmitService;
import com.baimao.oj.service.UserService;
import com.baimao.oj.utils.SqlUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.beans.Transient;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 */
@Service
@Slf4j
public class QuestionSubmitServiceImpl extends ServiceImpl<QuestionSubmitMapper, QuestionSubmit>
    implements QuestionSubmitService{
    
    @Resource
    private QuestionService questionService;

    @Resource
    private UserService userService;

    @Resource
    @Lazy
    private JudgeService judgeService;

    @Resource
    @Lazy
    private ContestRankService contestRankService;

    /**
     * 题目提交
     *
     * @param questionSubmitAddRequest 题目提交信息
     * @param loginUser
     * @return
     */
    @Override
    @Transactional  // 有外部调用，需要事务
    public QuestionSubmit doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser) {
        // 1. 校验编程语言是否合理
        String language = questionSubmitAddRequest.getLanguage();
        QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
        if(languageEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"编程语言错误");
        }

        // 2. 判断题目是否存在
        Long questionId = questionSubmitAddRequest.getQuestionId();
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 3. 构造提交记录
        long userId = loginUser.getId();
        QuestionSubmit questionSubmit = new QuestionSubmit();
        questionSubmit.setUserId(userId);
        questionSubmit.setQuestionId(questionId);
        questionSubmit.setCode(questionSubmitAddRequest.getCode());
        questionSubmit.setLanguage(questionSubmitAddRequest.getLanguage());
        /** 如果为比赛场景下提交，设置比赛 id */
        Long contestId = questionSubmitAddRequest.getContestId();
        if(ObjectUtils.isNotEmpty(contestId)) {
            questionSubmit.setContestId(contestId);
        }

        // 4. 设置初始状态
        questionSubmit.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        questionSubmit.setJudgeInfo("{}");
        boolean save = this.save(questionSubmit);
        if(!save){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"题目提交失败");
        }

        Long questionSubmitId = questionSubmit.getId();
        /** 4. 异步执行判题服务 */
//        CompletableFuture.runAsync(() -> {
//            judgeService.doJudge(questionSubmitId);
//        });

        QuestionSubmit questionSubmitResponse = judgeService.doJudge(questionSubmitId);

        // 修改题目的提交次数与通过次数
        String judgeInfoStr = questionSubmitResponse.getJudgeInfo();
        JudgeInfo judgeInfo = JSONUtil.toBean(judgeInfoStr, JudgeInfo.class);
        question.setSubNum(question.getSubNum() + 1);
        if(JudgeInfoMessageEnum.ACCEPTED.getText().equals(judgeInfo.getMessage())) {
            question.setAcceptedNum(question.getAcceptedNum() + 1);
        }
        boolean update = questionService.updateById(question);
        if(!update){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"题目更新失败");
        }

        // 提交成功后，事务同步管理刷新 Redis 排行缓存
//        refreshContestRankAfterCommit(questionSubmitResponse);
        
        // 这里可以直接操作redis（除非redis操作后面有可能抛异常导致数据库回滚的操作）
        contestRankService.refreshUserRankSnapshot(questionSubmitResponse);

//        int i = 0;
//        int i1 = 1 / i;

        return questionSubmitResponse;
    }


    @Override
    @Transactional  // 有外部调用，需要事务
    public QuestionSubmitVO doQuestionSubmitVO(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser) {
        QuestionSubmit questionSubmit = doQuestionSubmit(questionSubmitAddRequest, loginUser);
        QuestionSubmitVO questionSubmitVO = getQuestionSubmitVO(questionSubmit, loginUser);
        return questionSubmitVO;
    }

    /**
     * 获取查询包装类（用户可能根据某些字段查询，根据前端传来的查询对象生成 QueryWrapper）
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest) {
        QueryWrapper<QuestionSubmit> queryWrapper = new QueryWrapper<>();
        if (questionSubmitQueryRequest == null) {
            return queryWrapper;
        }
        String language = questionSubmitQueryRequest.getLanguage();
        Integer status = questionSubmitQueryRequest.getStatus();
        Long questionId = questionSubmitQueryRequest.getQuestionId();
        Long userId = questionSubmitQueryRequest.getUserId();
        String sortField = questionSubmitQueryRequest.getSortField();
        String sortOrder = questionSubmitQueryRequest.getSortOrder();
        String judgeStatus = questionSubmitQueryRequest.getJudgeStatus();

        // 拼接查询条件
        queryWrapper.eq(StringUtils.isNotBlank(language), "language", language);
        queryWrapper.eq(QuestionSubmitStatusEnum.getEnumByValue(status) != null, "status", status);
        queryWrapper.eq(ObjectUtils.isNotEmpty(questionId), "questionId", questionId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        /** 对应的判题结果 */
        queryWrapper.like(ObjectUtils.isNotEmpty(judgeStatus), "judgeInfo", judgeStatus);

        // 根据前端的逻辑，按提交时间降序排序
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    /**
     * 根据 QuestionSubmit 转换为题目提交展示信息（QuestionSubmitVO）【脱敏】
     *
     * @param questionSubmit
     * @param loginUser
     * @return
     */
    @Override
    public QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser) {
        QuestionSubmitVO questionSubmitVO = QuestionSubmitVO.objToVo(questionSubmit);
        /** 脱敏：仅本人和管理员可以查看提交的代码（答案） */
        long loginUserId = loginUser.getId();
        if(loginUserId != questionSubmit.getUserId() && !userService.isAdmin(loginUser)){
            questionSubmitVO.setCode(null);
        }
        Long questionId = questionSubmit.getQuestionId();
        QuestionVO questionVO = questionService.getQuestionVO(questionService.getById(questionId), null);
        questionSubmitVO.setQuestionVO(questionVO);
        UserVO userVO = userService.getUserVO(userService.getById(questionSubmit.getUserId()));
        questionSubmitVO.setUserVO(userVO);
        String errorCaseStr = questionSubmit.getErrorCase();
        if(StrUtil.isNotBlank(errorCaseStr)) {
            questionSubmitVO.setErrorCase(JSONUtil.toBean(errorCaseStr, JudgeCase.class));
        }
        return questionSubmitVO;
    }

    /**
     * 将分页查询的题目提交列表 QuestionSubmit 转为 QuestionSubmitVO 列表，即上面方法的循环
     *
     * @param questionSubmitPage
     * @return
     */
    @Override
    public Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser) {
        List<QuestionSubmit> questionSubmitList = questionSubmitPage.getRecords();
        Page<QuestionSubmitVO> questionSubmitVOPage = new Page<>(questionSubmitPage.getCurrent(), questionSubmitPage.getSize(), questionSubmitPage.getTotal());

        if (CollUtil.isEmpty(questionSubmitList)) {
            return questionSubmitVOPage;
        }
        // 1. 获取所有题目提交的用户信息
        
        // 2. 填充信息，将 QuestionSubmit 转换为 QuestionSubmitVO
        List<QuestionSubmitVO> questionSubmitVOList = questionSubmitList.stream().map(
                questionSubmit -> getQuestionSubmitVO(questionSubmit, loginUser)).collect(Collectors.toList());
        questionSubmitVOPage.setRecords(questionSubmitVOList);

        return questionSubmitVOPage;
    }

    @Override
    public List<QuestionSubmit> getQuestionSubmitPageByCIdAndUId(Long contestId, Long userId) {
        LambdaQueryWrapper<QuestionSubmit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QuestionSubmit::getContestId,contestId);
        queryWrapper.eq(QuestionSubmit::getUserId,userId);
        queryWrapper.orderByDesc(QuestionSubmit::getCreateTime);
        return this.list(queryWrapper);
    }

    /**
     * 排行榜缓存刷新放到事务提交后执行，避免数据库回滚时 Redis 已被提前更新。
     */
    private void refreshContestRankAfterCommit(QuestionSubmit questionSubmitResponse) {
        if (questionSubmitResponse == null || questionSubmitResponse.getContestId() == null || questionSubmitResponse.getContestId() <= 0) {
            return;
        }
        // 非事务上下文直接执行，兼容后续可能的复用场景。
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeUpdateContestRank(questionSubmitResponse);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeUpdateContestRank(questionSubmitResponse);
            }
        });
    }

    /**
        * 刷新缓存失败不影响提交流程，避免 Redis 短暂异常把主业务链路打断。
     */
    private void safeUpdateContestRank(QuestionSubmit questionSubmitResponse) {
        try {
            contestRankService.refreshUserRankSnapshot(questionSubmitResponse);
        } catch (Exception e) {
            log.warn("refresh contest rank snapshot after commit failed, submitId={}", questionSubmitResponse.getId(), e);
        }
    }

}




