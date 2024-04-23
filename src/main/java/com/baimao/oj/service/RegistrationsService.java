package com.baimao.oj.service;

import com.baimao.oj.model.dto.contest.ContestUserVOQueryRequest;
import com.baimao.oj.model.entity.Registrations;
import com.baimao.oj.model.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *
 */
public interface RegistrationsService extends IService<Registrations> {

    /**
     * 校验
     *
     * @param registration
     * @param add
     */
    void validRegistration(Registrations registration, boolean add);

    /**
     * 某个比赛报名的人数
     */
    Long getRegistrationCountByContestId(Long contestId);
}
