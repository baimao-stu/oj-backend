package com.baimao.oj.model.vo;

import cn.hutool.json.JSONUtil;
import com.baimao.oj.model.dto.question.JudgeConfig;
import com.baimao.oj.model.entity.Contest;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.util.Date;
import java.util.List;

/**
 * @author baimao
 * @title ContestVO
 */
@Data
public class ContestVO {

    /**
     * id
     */
    private Long id;

    /**
     * 竞赛标题
     */
    private String title;

    /**
     * 竞赛描述（非必要，可为NULL）
     */
    private String description;

    /**
     * 竞赛类型（0-基础、1-提高、2-进阶）
     */
    private Integer type;

    /**
     * 是否公开（0-不公开、1-公开）
     */
    private Integer isPublic;

    /**
     * 上限人数
     */
    private Integer pLimit;

    /**
     * 开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /**
     * 结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;


    /**
     * 用户信息
     */
    private UserVO userVO;

    /**
     * 对象转包装类
     *
     * @param contest
     * @return
     */
    public static ContestVO objToVo(Contest contest) {
        if (contest == null) {
            return null;
        }
        ContestVO contestVO = new ContestVO();
        BeanUtils.copyProperties(contest, contestVO);
        return contestVO;
    }
}
