package com.baimao.oj.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baimao.oj.common.ErrorCode;
import com.baimao.oj.exception.BusinessException;
import com.baimao.oj.exception.ThrowUtils;
import com.baimao.oj.model.entity.Contest;
import com.baimao.oj.service.ContestService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baimao.oj.model.entity.Registrations;
import com.baimao.oj.service.RegistrationsService;
import com.baimao.oj.mapper.RegistrationsMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Date;

/**
 *
 */
@Service
public class RegistrationsServiceImpl extends ServiceImpl<RegistrationsMapper, Registrations>
    implements RegistrationsService{

    @Resource
    private ContestService contestService;

    @Override
    @Transactional
    public void validRegistration(Registrations registration, boolean add) {
        if (registration == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long contestId = registration.getContestId();
        // 有参数则校验
        if (ObjectUtil.isEmpty(contestId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "竞赛id为空");
        }
        Contest contest = contestService.getById(contestId);
        if(contest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"该竞赛不存在");
        }

        LambdaQueryWrapper<Registrations> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Registrations::getContestId,contestId);
        queryWrapper.eq(Registrations::getUserId,registration.getUserId());
        long count = this.count(queryWrapper);
        if(count > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"已报名该竞赛");
        }

        Date now = registration.getJoinTime();
        Date startTime = contest.getStartTime();
        /**
         * 已开始，不能再报名
         */
        if(now.after(startTime)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"比赛时间已开始，不能再报名！");
        }

        Long regisCount = getRegistrationCountByContestId(contestId);
        /**
         * 人数已达上限，不能再报名
         */
        if(regisCount + 1 > contest.getPLimit()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"报名人数已达上限");
        }

    }

    @Override
    public Long getRegistrationCountByContestId(Long contestId) {
        if (contestId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        LambdaQueryWrapper<Registrations> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Registrations::getContestId,contestId);
        return this.count(queryWrapper);
    }
}



