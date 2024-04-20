package com.baimao.oj.service;

import com.baimao.oj.model.dto.contest.ContestQueryRequest;
import com.baimao.oj.model.entity.Contest;
import com.baimao.oj.model.vo.ContestVO;
import com.baimao.oj.model.vo.QuestionVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 *
 */
public interface ContestService extends IService<Contest> {

    /**
     * 校验
     *
     * @param contest
     * @param add
     */
    void validContest(Contest contest, boolean add);

    /**
     * 获取竞赛封装（封装用户信息）
     *
     * @param contest
     * @param request
     * @return
     */
    ContestVO getContestVO(Contest contest, HttpServletRequest request);

    /**
     * 获取查询条件
     *
     * @param contestQueryRequest
     * @return
     */
    QueryWrapper<Contest> getQueryWrapper(ContestQueryRequest contestQueryRequest);

    Page<ContestVO> getContestVOPage(Page<Contest> contestPage, HttpServletRequest request);

    /**
     * 修改比赛信息
     * @param contest       //修改后的比赛信息
     * @param questionIdList    //比赛修改后的题单id列表
     * @return
     */
    Boolean editById(Contest contest, List<Long> questionIdList);
}
