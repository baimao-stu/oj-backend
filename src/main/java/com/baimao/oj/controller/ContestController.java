package com.baimao.oj.controller;

import cn.hutool.json.JSONUtil;
import com.baimao.oj.annotation.AuthCheck;
import com.baimao.oj.common.BaseResponse;
import com.baimao.oj.common.DeleteRequest;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.common.ResultUtils;
import com.baimao.oj.constant.UserConstant;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.exception.ThrowUtils;
import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.dto.contest.*;
import com.baimao.oj.model.entity.*;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baimao.oj.model.vo.ContestVO;
import com.baimao.oj.model.vo.QuestionVO;
import com.baimao.oj.model.vo.UserVO;
import com.baimao.oj.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
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
        boolean result = contestService.save(contest);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        /**
         * 关联比赛-题目表
         */
        long newContestId = contest.getId();
        List<ContestQuestion> contestQuestionList = new ArrayList<>();
        for (int i = 0; i < questionIdList.size(); i++) {
            ContestQuestion contestQuestion = ContestQuestion.builder()
                    .contestId(newContestId).questionId(questionIdList.get(i)).sequence(i).build();
            contestQuestionList.add(contestQuestion);
        }
        log.info(contestQuestionList.toString());
        boolean result2 = contestQuestionService.saveBatch(contestQuestionList);
        ThrowUtils.throwIf(!result2, ErrorCode.OPERATION_ERROR);

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
         * 1.删除竞赛本身
         */
        boolean b = contestService.removeById(id);
        if (!b) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        /**
         * 2.级联删除该竞赛下的题目提交记录
         */
        LambdaQueryWrapper<QuestionSubmit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QuestionSubmit::getContestId, id);
        long count = questionSubmitService.count(queryWrapper);
        boolean remove = questionSubmitService.remove(queryWrapper);
        if (!remove && count > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }

        /**
         * 3.级联删除该竞赛下的题目（竞赛-题目表）
         */
        LambdaQueryWrapper<ContestQuestion> queryWrapper2 = new LambdaQueryWrapper<>();
        queryWrapper2.eq(ContestQuestion::getContestId, id);
        long count2 = contestQuestionService.count(queryWrapper2);
        boolean remove2 = contestQuestionService.remove(queryWrapper2);

        if (!remove2 && count2 > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }

        return ResultUtils.success(remove);
    }

    /**
     * 更新（仅管理员，edit是用户也可访问修改自己创建的问题）
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
        Page<Contest> contestPage = contestService.page(new Page<>(current, size),
                contestService.getQueryWrapper(contestQueryRequest));
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
        User loginUser = userService.getLoginUser(request);
        /**将查询的userId改为当前登录用户*/
        contestQueryRequest.setUserId(loginUser.getId());
        long current = contestQueryRequest.getCurrent();
        long size = contestQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Contest> contestPage = contestService.page(new Page<>(current, size),
                contestService.getQueryWrapper(contestQueryRequest));
        return ResultUtils.success(contestService.getContestVOPage(contestPage, request));
    }


    /**
     * 编辑（用户），与上面的update差不多
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
        registration.setRank(0);    //排名默认设置为0

        registrationsService.validRegistration(registration, true);

        boolean result = registrationsService.save(registration);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        Long registrationId = registration.getId();

        return ResultUtils.success(registrationId);
    }

    /**
     * 获取当前用户某个比赛的报名记录
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
     * 获取某个比赛下的排名情况（所有报名的用户及其做题情况）
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
        LambdaQueryWrapper<Registrations> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Registrations::getContestId,contestId);
        //报名该比赛的用户id列表
        List<Long> userIdList = registrationsService.list(queryWrapper)
                .stream().map(Registrations::getUserId).collect(Collectors.toList());
        LambdaQueryWrapper<User> queryWrapper2 = new LambdaQueryWrapper<>();
        queryWrapper2.in(User::getId,userIdList);
        //报名该比赛的用户列表
        List<User> userList = userService.list(queryWrapper2);
        //报名该比赛的用户VO列表
        List<UserVO> userVOList = userService.getUserVO(userList);


        List<ContestUserVO> contestUserVOList = new ArrayList<>();
        for(int i = 0;i < userVOList.size();i ++) {
            Map<Long,Long> questionIdACMilli = new HashMap<>();       //题目id与其在ac的情况下最短的消耗时间
            Map<Long,String> questionIdJudgeMsg = new HashMap<>(); //题目id与其最后的判题状态（有ac以ac优先）
            Map<Long, JudgeInfo> questionSubmitStatus = new HashMap<>(); //用户在这场比赛的提交记录情况（有ac以ac为准，没ac已最后一次提交为准）

            ContestUserVO contestUserVO = new ContestUserVO();
            UserVO userVO = userVOList.get(i);
            //本次比赛该用户的所有提交记录
            List<QuestionSubmit> questionSubmits = questionSubmitService.getQuestionSubmitPageByCIdAndUId(contestId, userVO.getId());
            int acNum = 0;        //通过的题目数量
            long allTime = 0l;  //消耗总时长

            /** 1.首先过滤ac的判题结果 */
            for(int j = 0;j < questionSubmits.size();j ++) {
                QuestionSubmit questionSubmit = questionSubmits.get(j);
                String judgeInfoStr = questionSubmit.getJudgeInfo();
                JudgeInfo judgeInfo = JSONUtil.toBean(judgeInfoStr, JudgeInfo.class);
                if(judgeInfo == null) continue;
                Long questionId = questionSubmit.getQuestionId();
                Long time = judgeInfo.getTime();
                String judgeMsg = judgeInfo.getMessage();
                //ac才记录时间
                if("Accepted".equals(judgeMsg)) {
                    if(questionIdACMilli.get(questionId) != null) {
                        Long oldTime = questionIdACMilli.get(questionId);
                        if(time < oldTime) {
                            questionIdACMilli.put(questionId,time);
                            allTime -= oldTime - time;
                            questionSubmitStatus.put(questionId,judgeInfo);
                        }
                    }else {
                        questionIdACMilli.put(questionId,time);
                        allTime += time;
                        questionSubmitStatus.put(questionId,judgeInfo);
                        acNum ++;
                    }
                    questionIdJudgeMsg.put(questionId,judgeMsg);
                }
            }
            /** 2.过滤非ac的判题结果 */
            for(int j = 0;j < questionSubmits.size();j ++) {
                QuestionSubmit questionSubmit = questionSubmits.get(j);
                String judgeInfoStr = questionSubmit.getJudgeInfo();
                JudgeInfo judgeInfo = JSONUtil.toBean(judgeInfoStr, JudgeInfo.class);
                if(judgeInfo == null) continue;
                Long questionId = questionSubmit.getQuestionId();
                String judgeMsg = judgeInfo.getMessage();
                //非ac记录判题结果
                if(!"Accepted".equals(judgeMsg)) {
                    /**
                     * 1.非ac的情况，如同个题目还有ac的情况，以ac的情况为准
                     * 2.非ac的情况，由于提交记录已经按提交时间倒序排序过，以最后提交的一种情况为准
                     */
                    if(questionIdACMilli.get(questionId) == null && questionIdJudgeMsg.get(questionId) == null) {
                        questionIdJudgeMsg.put(questionId,judgeMsg);
                        questionSubmitStatus.put(questionId,judgeInfo);
                    }
                }
            }

            log.info("用户id:" + userVO.getId() + "在比赛id:" + contestId + "的提交情况");
            log.info("题目id与时间：" + questionIdACMilli + " " + allTime);
            log.info("题目id与判题结果：" + questionIdJudgeMsg);

            contestUserVO.setUserVO(userVO);        //用户信息
            contestUserVO.setAllTime(allTime);  //总消耗时间
            contestUserVO.setAcNum(acNum);          //ac的题目数
            contestUserVO.setQuestionSubmitStatus(questionSubmitStatus);    //做题情况
            contestUserVOList.add(contestUserVO);

            log.info(contestUserVO.toString());
        }

        Page<ContestUserVO> contestUserVOPage = new Page<>(current,size);
        contestUserVOPage.setRecords(contestUserVOList);
        return ResultUtils.success(contestUserVOPage);
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
    public BaseResponse<List<QuestionVO>> listQuestionVOByContestId(Long contestId, HttpServletRequest request) {
        if(contestId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //获取竞赛下的所有题目id
        LambdaQueryWrapper<ContestQuestion> queryWrapper1 = new LambdaQueryWrapper<>();
        queryWrapper1.eq(ContestQuestion::getContestId,contestId);
        List<Long> questionIdList = contestQuestionService.list(queryWrapper1)
                .stream().map(ContestQuestion::getQuestionId).collect(Collectors.toList());
        log.info("竞赛下的题目id列表：" + questionIdList);

        //获取竞赛下的所有题目
        LambdaQueryWrapper<Question> queryWrapper2 = new LambdaQueryWrapper<>();
        queryWrapper2.in(Question::getId,questionIdList);
        List<Question> questionList = questionService.list(queryWrapper2);
        log.info("竞赛下的题目列表：" + questionList);

        Page<Question> questionPage = new Page();
        questionPage.setRecords(questionList);
        List<QuestionVO> questionVOList = questionService.getQuestionVOPage(questionPage, request).getRecords();

        return ResultUtils.success(questionVOList);
    }

}
