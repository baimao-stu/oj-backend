package com.baimao.oj.model.dto.contest;

import com.baimao.oj.common.PageRequest;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
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
     * id
     */
    @TableId(type = IdType.AUTO)
    private Long id;

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
    private Date startTime;

    /**
     * 结束时间
     */
    private Date endTime;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 用户id
     */
    private Long userId;

}
