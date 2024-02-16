package com.baimao.oj.judge.codesangbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author baimao
 * @title ExecuteCodeResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponse {

    //执行程序后得到的一组输出结果
    private  List<String> output;

    //接口信息
    private String message;

    /**
     * 执行状态
     * 测试用例全通过：1
     * 代码编译错误：2
     * 程序执行过程出错：3
     */
    private Integer status;

    //判题信息(花费的内存，时间)
    private JudgeInfo judgeInfo;
}
