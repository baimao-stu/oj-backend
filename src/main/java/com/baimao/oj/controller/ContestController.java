package com.baimao.oj.controller;

import com.baimao.oj.annotation.AuthCheck;
import com.baimao.oj.common.BaseResponse;
import com.baimao.oj.common.DeleteRequest;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.common.ResultUtils;
import com.baimao.oj.constant.UserConstant;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.exception.ThrowUtils;
import com.baimao.oj.model.dto.contest.*;
import com.baimao.oj.model.entity.*;
import com.baimao.oj.model.vo.*;
import com.baimao.oj.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 竞赛接口
 */
@RestController
@RequestMapping("/contest")
@Slf4j
public class ContestController {

    @Resource
    private ContestService contestService;

    @Resource
    private ContestQuestionService contestQuestionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private RegistrationsService registrationsService;

    @Resource
    private QuestionService questionService;

    @Resource
    private UserService userService;

    @Resource
    private ContestRankService contestRankService;

    // region 增删改查

    /**
        * 创建竞赛
     *
     * @param contestAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addContest(@RequestBody ContestAddRequest contestAddRequest, HttpServletRequest request) {
        if (contestAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Contest contest = new Contest();
        BeanUtils.copyProperties(contestAddRequest, contest);
        List<Long> questionIdList = contestAddRequest.getQuestionIdList();
        if (questionIdList == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"题单为空");
        }
        contestService.validContest(contest, true);
        User loginUser = userService.getLoginUser(request);
        contest.setUserId(loginUser.getId());

        long newContestId = contestService.saveContest(contest,questionIdList,loginUser.getId());
        return ResultUtils.success(newContestId);
    }

    /**
        * 删除
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    @Transactional
    public BaseResponse<Boolean> deleteContest(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();

        // 判断是否存在
        Contest oldContest = contestService.getById(id);
        ThrowUtils.throwIf(oldContest == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldContest.getUserId().equals(user.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        /**
         * 1. 删除竞赛本身
         */
        boolean b = contestService.removeById(id);
        if (!b) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        /**
         * 2. 级联删除该竞赛下的题目提交记录
         */
        LambdaQueryWrapper<QuestionSubmit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QuestionSubmit::getContestId, id);
        long count = questionSubmitService.count(queryWrapper);
        boolean remove = questionSubmitService.remove(queryWrapper);
        if (!remove && count > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }

        /**
         * 3. 级联删除该竞赛下的题目（竞赛-题目表）
         */
        LambdaQueryWrapper<ContestQuestion> queryWrapper2 = new LambdaQueryWrapper<>();
        queryWrapper2.eq(ContestQuestion::getContestId, id);
        long count2 = contestQuestionService.count(queryWrapper2);
        boolean remove2 = contestQuestionService.remove(queryWrapper2);

        if (!remove2 && count2 > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }

        removeContestRankDataAfterCommit(id);

        return ResultUtils.success(remove);
    }

    /**
        * 更新（仅管理员，edit 是用户也可访问修改自己创建的问题）
     *
     * @param contestUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateContest(@RequestBody ContestUpdateRequest contestUpdateRequest) {
        if (contestUpdateRequest == null || contestUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Contest contest = new Contest();
        BeanUtils.copyProperties(contestUpdateRequest, contest);
        List<Long> questionIdList = contestUpdateRequest.getQuestionIdList();
        if (questionIdList == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"题单为空");
        }
        // 参数校验
        contestService.validContest(contest, false);
        long id = contestUpdateRequest.getId();
        // 判断是否存在
        Contest oldContest = contestService.getById(id);
        ThrowUtils.throwIf(oldContest == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = contestService.updateById(contest);
        if (result) {
            safeRemoveContestRankData(id);
        }
        return ResultUtils.success(result);
    }

    /**
        * 根据 id 获取
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<Contest> getContestById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Contest contest = contestService.getById(id);
        if (contest == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 只有本人和管理员才能执行操作
        User loginUser = userService.getLoginUser(request);
        if (!loginUser.getId().equals(contest.getUserId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return ResultUtils.success(contest);
    }

    /**
        * 根据 id 获取（封装用户信息）
     *
     * @param id
     * @return
     */
    @GetMapping("/get/vo")
    public BaseResponse<ContestVO> getContestVOById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Contest contest = contestService.getById(id);
        if (contest == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(contestService.getContestVO(contest, request));
    }


    /**
        * 分页获取列表（封装类）
     *
     * @param contestQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<ContestVO>> listContestVOByPage(@RequestBody ContestQueryRequest contestQueryRequest,
                                                             HttpServletRequest request) {
        long current = contestQueryRequest.getCurrent();
        long size = contestQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);

        User loginUser = userService.getLoginUser(request);
        QueryWrapper<Contest> queryWrapper = contestService.getQueryWrapper(contestQueryRequest, request);
        /** 比赛是公开的，或者是自己创建的，才能查看 */
        queryWrapper.and(wrapper -> {
            wrapper.eq("isPublic",1).or().eq("userId",loginUser.getId());
        });
        Page<Contest> contestPage = contestService.page(new Page<>(current, size),
                queryWrapper);

        return ResultUtils.success(contestService.getContestVOPage(contestPage, request));
    }

    /**
        * 分页获取当前用户创建的资源列表
     *
     * @param contestQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<ContestVO>> listMyContestVOByPage(@RequestBody ContestQueryRequest contestQueryRequest,
                                                               HttpServletRequest request) {
        if (contestQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long current = contestQueryRequest.getCurrent();
        long size = contestQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);

        User loginUser = userService.getLoginUser(request);
        QueryWrapper<Contest> queryWrapper = contestService.getQueryWrapper(contestQueryRequest, request);
        /** 比赛是自己创建的，才能查看 */
        queryWrapper.eq("userId",loginUser.getId());
        Page<Contest> contestPage = contestService.page(new Page<>(current, size),
                queryWrapper);
        return ResultUtils.success(contestService.getContestVOPage(contestPage, request));
    }


    /**
        * 编辑（用户），与上面的 update 差不多
     *
     * @param contestEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editContest(@RequestBody ContestEditRequest contestEditRequest, HttpServletRequest request) {
        if (contestEditRequest == null || contestEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Contest contest = new Contest();
        BeanUtils.copyProperties(contestEditRequest, contest);
        List<Long> questionIdList = contestEditRequest.getQuestionIdList();
        if (questionIdList == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"题单为空");
        }

        // 参数校验
        contestService.validContest(contest, false);
        User loginUser = userService.getLoginUser(request);
        long id = contestEditRequest.getId();
        // 判断是否存在
        Contest oldContest = contestService.getById(id);
        ThrowUtils.throwIf(oldContest == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑
        if (!oldContest.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = contestService.editById(contest,contestEditRequest.getQuestionIdList());
        if (result) {
            safeRemoveContestRankData(id);
        }
        return ResultUtils.success(result);
    }

//-------------------------用户报名接口----------------------------

    /**
        * 用户报名
     *
     * @param registrationsAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add/registration")
    public BaseResponse<Long> addRegistration(@RequestBody RegistrationsAddRequest registrationsAddRequest, HttpServletRequest request) {
        if (registrationsAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Registrations registration = new Registrations();
        BeanUtils.copyProperties(registrationsAddRequest, registration);

        User loginUser = userService.getLoginUser(request);
        registration.setUserId(loginUser.getId());
        registration.setJoinTime(new Date());
        registration.setRank(0);    // 排名默认设置为 0

        registrationsService.validRegistration(registration, true);

        boolean result = registrationsService.save(registration);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 报名成功后先初始化一条空快照，保证零提交用户也能进入排行榜。
        contestRankService.initUserRankSnapshot(registration.getContestId(), registration.getUserId());
        Long registrationId = registration.getId();

        return ResultUtils.success(registrationId);
    }

    /**
        * 获取当前用户某个比赛的报名记录（即用户是否报名了某个比赛）
     *
     * @param contestId
     * @param request
     * @return
     */
    @PostMapping("/get/registrationByContestId")
    public BaseResponse<Registrations> getRegistrationByContestId(Long contestId, HttpServletRequest request) {
        if (contestId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        LambdaQueryWrapper<Registrations> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Registrations::getContestId,contestId);
        queryWrapper.eq(Registrations::getUserId,loginUser.getId());
        Registrations registration = registrationsService.getOne(queryWrapper);
        return ResultUtils.success(registration);
    }

    /**
        * 获取当前用户报名的比赛列表
     *
     * @param contestQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/myJoinContestVOPage")
    public BaseResponse<Page<ContestVO>> listMyJoinContestVOPage(@RequestBody ContestQueryRequest contestQueryRequest,
                                                                 HttpServletRequest request) {
        if (contestQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long current = contestQueryRequest.getCurrent();
        long size = contestQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        /** 当前用户的所有报名 contestId */
        LambdaQueryWrapper<Registrations> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Registrations::getUserId,loginUser.getId());
        List<Long> contestIdList = registrationsService.list(queryWrapper)
                .stream().map(Registrations::getContestId).collect(Collectors.toList());
        if(contestIdList.size() == 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"获取报名用户异常");
        }
        QueryWrapper<Contest> queryWrapper2 = contestService.getQueryWrapper(contestQueryRequest,request);
        queryWrapper2.in("id", contestIdList);
        /** 获取用户所有报名的比赛 */
        Page<Contest> contestPage = contestService.page(new Page<>(current, size), queryWrapper2);
        return ResultUtils.success(contestService.getContestVOPage(contestPage, request));
    }

    /**
        * 获取某个比赛下的排名情况（所有报名用户及其做题情况）
     *
     * @param contestUserVOQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/rankByContestIdByPage")
    public BaseResponse<Page<ContestUserVO>> listRankByContestIdByPage(@RequestBody ContestUserVOQueryRequest contestUserVOQueryRequest, HttpServletRequest request) {
        if (contestUserVOQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long current = contestUserVOQueryRequest.getCurrent();
        long size = contestUserVOQueryRequest.getPageSize();
        Long contestId = contestUserVOQueryRequest.getContestId();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        // 排行榜统一下沉到排行榜服务，控制器不再做全量聚合。
        Page<ContestUserVO> contestUserVOPage = contestRankService.listContestRankPage(contestId, current, size);
        return ResultUtils.success(contestUserVOPage);
    }

    /**
        * 删除比赛时等待事务提交后再清缓存，避免数据库回滚导致缓存被误删。
     */
    private void removeContestRankDataAfterCommit(Long contestId) {
        if (contestId == null || contestId <= 0) {
            return;
        }
        /**
         * 当前线程是否“激活了事务同步机制”，只要是在 @Transactional 方法执行过程中（方法还没结束），这个返回值一定是 true
         */
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeRemoveContestRankData(contestId);
            return;
        }
        /**
         * Spring 的事务上下文绑定在当前线程（ThreadLocal）上
         * 注册的 TransactionSynchronization 回调，也只会绑定到当前正在执行的这个事务中
         */
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeRemoveContestRankData(contestId);
            }
        });
    }

    /**
        * 缓存清理失败只记录日志，避免影响控制器返回。
     */
    private void safeRemoveContestRankData(Long contestId) {
        try {
            contestRankService.removeContestRankData(contestId);
        } catch (Exception e) {
            log.warn("remove contest rank data failed, contestId={}", contestId, e);
        }
    }

    /**
        * 获取某个比赛的报名人数
     * @param contestId
     * @param request
     * @return
     */
    @GetMapping("/get/count/registration")
    public BaseResponse<Long> getRegistrationCount(Long contestId, HttpServletRequest request) {
        long count = registrationsService.getRegistrationCountByContestId(contestId);
        return ResultUtils.success(count);
    }

    /**
        * 获取某个比赛的题目数量
     * @param contestId
     * @param request
     * @return
     */
    @GetMapping("/get/count/question")
    public BaseResponse<Long> getQuestionCount(Long contestId, HttpServletRequest request) {
        if (contestId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        LambdaQueryWrapper<ContestQuestion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ContestQuestion::getContestId,contestId);
        long count = contestQuestionService.count(queryWrapper);
        return ResultUtils.success(count);
    }


    //-------------------------竞赛-题目接口----------------------------
    /**
        * 获取某个竞赛下的题目列表
     *
     * @param contestId
     * @param request
     * @return
     */
    @PostMapping("/list/questionVOByContestId")
    public BaseResponse<List<ContestQuestionVO>> listContestQuestionVOByContestId(Long contestId, HttpServletRequest request) {
        if(contestId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取竞赛下的所有题目 id
        LambdaQueryWrapper<ContestQuestion> queryWrapper1 = new LambdaQueryWrapper<>();
        queryWrapper1.eq(ContestQuestion::getContestId,contestId);
        List<ContestQuestion> contestQuestionList = contestQuestionService.list(queryWrapper1);
        Collections.sort(contestQuestionList);
        List<String> questionIdList = contestQuestionList
                .stream().map(contestQuestion -> contestQuestion.getQuestionId().toString()).collect(Collectors.toList());
        if(questionIdList.size() == 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"比赛题目为空");
        }
        log.info("竞赛下的题目 id 列表：{}", questionIdList);

        // 获取竞赛下的所有题目
        LambdaQueryWrapper<Question> queryWrapper2 = new LambdaQueryWrapper<>();
        queryWrapper2.in(Question::getId,questionIdList);
        /** 按查询到的题目序号排序 */
        queryWrapper2.last("order by field(id," + String.join(",",questionIdList) + ")");
        List<Question> questionList = questionService.list(queryWrapper2);
        log.info("竞赛下的题目列表：{}", questionList);

        Page<Question> questionPage = new Page();
        questionPage.setRecords(questionList);
        List<QuestionVO> questionVOList = questionService.getQuestionVOPage(questionPage, request).getRecords();

        List<ContestQuestionVO> contestQuestionVOList = new ArrayList<>();
        /**
         * 每道题目的提交情况
         */
        User loginUser = userService.getLoginUser(request);
        long userId = loginUser.getId();
        for (int i = 0;i < questionVOList.size();i ++) {
            LambdaQueryWrapper<QuestionSubmit> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(QuestionSubmit::getContestId, contestId);
            queryWrapper.eq(QuestionSubmit::getQuestionId, questionVOList.get(i).getId());
            queryWrapper.eq(QuestionSubmit::getUserId, userId);
            long count = questionSubmitService.count(queryWrapper);
            ContestQuestionVO contestQuestionVO = new ContestQuestionVO();
            BeanUtils.copyProperties(questionVOList.get(i),contestQuestionVO);
            contestQuestionVO.setIsSubmit(count > 0);
            contestQuestionVOList.add(contestQuestionVO);
        }

        return ResultUtils.success(contestQuestionVOList);
    }

}
