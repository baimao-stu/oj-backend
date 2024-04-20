package com.baimao.oj.model.dto.contest;

import com.baimao.oj.model.entity.Question;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * @author baimao
 * @title ContestAddRequest
 */
@Data
public class ContestAddRequest implements Serializable {

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
     * 题目id列表
     */
    private List<Long> questionIdList;

}
