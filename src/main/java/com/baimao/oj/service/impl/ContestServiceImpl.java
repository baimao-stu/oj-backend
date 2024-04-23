package com.baimao.oj.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.constant.CommonConstant;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.exception.ThrowUtils;
import com.baimao.oj.model.dto.contest.ContestQueryRequest;
import com.baimao.oj.model.entity.ContestQuestion;
import com.baimao.oj.model.entity.Registrations;
import com.baimao.oj.model.entity.User;
import com.baimao.oj.model.vo.ContestVO;
import com.baimao.oj.model.vo.UserVO;
import com.baimao.oj.service.ContestQuestionService;
import com.baimao.oj.service.RegistrationsService;
import com.baimao.oj.service.UserService;
import com.baimao.oj.utils.SqlUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baimao.oj.model.entity.Contest;
import com.baimao.oj.service.ContestService;
import com.baimao.oj.mapper.ContestMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 */
@Service
@Slf4j
public class ContestServiceImpl extends ServiceImpl<ContestMapper, Contest>
        implements ContestService {


    @Resource
    private UserService userService;

    @Resource
    private ContestQuestionService contestQuestionService;

    @Resource
    @Lazy
    private RegistrationsService registrationsService;

    @Override
    public void validContest(Contest contest, boolean add) {
        if (contest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        String title = contest.getTitle();
        String description = contest.getDescription();
        Integer type = contest.getType();
        Integer isPublic = contest.getIsPublic();
        Integer pLimit = contest.getPLimit();
        Date startTime = contest.getStartTime();
        Date endTime = contest.getEndTime();

        // 创建时（修改时用add区分），对应参数不能为空
        if (add) {
            ThrowUtils.throwIf(StringUtils.isAnyBlank(title), ErrorCode.PARAMS_ERROR);
        }
        // 有参数则校验
        if (StringUtils.isNotBlank(title) && title.length() > 80) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题过长");
        }
        if (StringUtils.isNotBlank(description) && description.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "描述过长");
        }
        if (ObjectUtil.isEmpty(type)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "类型为空");
        }
        if (ObjectUtil.isEmpty(isPublic)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "是否空开为空");
        }
        if (ObjectUtil.isEmpty(pLimit)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "限制人数为空");
        }
        if (ObjectUtil.isEmpty(startTime)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "开始时间为空");
        }
        if (ObjectUtil.isEmpty(endTime)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "结束时间为空");
        }
        if (endTime.before(startTime)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "开始时间晚于结束时间");
        }

    }

    /**
     * 根据Contest转换为问题展示信息（ContestVO）【脱敏】
     *
     * @param contest
     * @param request
     * @return
     */
    @Override
    public ContestVO getContestVO(Contest contest, HttpServletRequest request) {
        ContestVO contestVO = ContestVO.objToVo(contest);
        // 1. 关联查询用户信息
        Long userId = contest.getUserId();
        User user = null;
        if (userId != null && userId > 0) {
            user = userService.getById(userId);
        }
        UserVO userVO = userService.getUserVO(user);
        contestVO.setUserVO(userVO);
        return contestVO;
    }

    /**
     * 获取查询包装类（用户可能根据哪些字段查询，根据前端传来的查询对象生成QueryWrapper类）
     *
     * @param contestQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Contest> getQueryWrapper(ContestQueryRequest contestQueryRequest,HttpServletRequest request) {
        QueryWrapper<Contest> queryWrapper = new QueryWrapper<>();
        if (contestQueryRequest == null) {
            return queryWrapper;
        }
        String title = contestQueryRequest.getTitle();
        Integer type = contestQueryRequest.getType();
//        Date startTime = contestQueryRequest.getStartTime();
//        Date endTime = contestQueryRequest.getEndTime();
//        Long userId = contestQueryRequest.getUserId();
        String userName = contestQueryRequest.getUserName();
        String sortField = contestQueryRequest.getSortField();
        String sortOrder = contestQueryRequest.getSortOrder();

        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        /** 根据举办者名字模糊查找比赛 */
        userQueryWrapper.like(StringUtils.isNotBlank(userName), "userName", userName);
        List<Long> userIdList = userService.list(userQueryWrapper)
                .stream().map(User::getId).collect(Collectors.toList());


        //拼接查询条件
        queryWrapper.like(StringUtils.isNotBlank(title), "title", title);
        queryWrapper.in(ObjectUtils.isNotEmpty(userIdList), "userId", userIdList);
        queryWrapper.eq(ObjectUtils.isNotEmpty(type), "type", type);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    /**
     * 把分页查询的问题列表转为QuestionVO列表，即上面方法的循环
     *
     * @param contestPage
     * @param request
     * @return
     */
    @Override
    public Page<ContestVO> getContestVOPage(Page<Contest> contestPage, HttpServletRequest request) {
        List<Contest> contestList = contestPage.getRecords();
        Page<ContestVO> contestVOPage = new Page<>(contestPage.getCurrent(), contestPage.getSize(), contestPage.getTotal());
        if (CollUtil.isEmpty(contestList)) {
            return contestVOPage;
        }
        // 1. 获取所有问题的用户信息
        Set<Long> userIdSet = contestList.stream().map(Contest::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息，Contest转换为ContestVO
        List<ContestVO> contestVOList = contestList.stream().map(contest -> {
            ContestVO contestVO = ContestVO.objToVo(contest);
            Long userId = contest.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            contestVO.setUserVO(userService.getUserVO(user));
            return contestVO;
        }).collect(Collectors.toList());
        contestVOPage.setRecords(contestVOList);
        return contestVOPage;
    }

    @Override
    @Transactional
    public Boolean editById(Contest contest, List<Long> questionIdList) {
        boolean update = this.updateById(contest);
        if(!update) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"比赛修改失败");
        }
        LambdaQueryWrapper<ContestQuestion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ContestQuestion::getContestId,contest.getId());
        /** 比赛原本的题单 */
        List<Long> oldQuestionIdList = contestQuestionService.list(queryWrapper)
                .stream().map(ContestQuestion::getQuestionId).collect(Collectors.toList());

        Set<Long> oldSet = new HashSet<>(oldQuestionIdList);
        Set<Long> set = new HashSet<>(questionIdList);
        System.out.println("旧id列表：" + oldSet);
        System.out.println("新id列表：" + set);
        if(!oldSet.equals(set)) {
            LambdaQueryWrapper<ContestQuestion> queryWrapper2 = new LambdaQueryWrapper<>();
            queryWrapper2.in(ContestQuestion::getContestId, contest.getId());
            boolean removeOld = contestQuestionService.remove(queryWrapper2);
            if(!removeOld) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR);
            }
            List<ContestQuestion> contestQuestionList = new ArrayList<>();
            for (int i = 0;i < questionIdList.size();i ++) {
                ContestQuestion contestQuestion = ContestQuestion.builder()
                        .contestId(contest.getId()).questionId(questionIdList.get(i)).sequence(i).build();
                contestQuestionList.add(contestQuestion);
            }
            boolean save = contestQuestionService.saveBatch(contestQuestionList);
            if(!save) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR);
            }
        }

        return true;
    }

    @Override
    @Transactional
    public Long saveContest(Contest contest, List<Long> questionIdList,Long userId) {
        boolean result = this.save(contest);
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

        /**
         * 创建人自动报名该比赛
         */
        Registrations registration = new Registrations();
        registration.setContestId(newContestId);
        registration.setUserId(userId);
        registration.setJoinTime(new Date());
        registration.setRank(0);    //排名默认设置为0
        boolean save = registrationsService.save(registration);
        ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR);

        return newContestId;
    }
}




