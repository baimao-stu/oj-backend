package com.baimao.oj.model.dto.contest;

import com.baimao.oj.common.PageRequest;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/**
 * @author baimao
 * @title ContestQueryRequest
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContestQueryRequest extends PageRequest implements Serializable {

    /**
     * 竞赛标题
     */
    private String title;


    /**
     * 竞赛类型（0-基础、1-提高、2-进阶）
     */
    private Integer type;


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
     * 用户名
     */
    private String userName;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 创建时间（用来排序）
     */
    private Date createTime;

}
