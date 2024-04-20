package com.baimao.oj.judge.codesangbox.model;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * @author baimao
 * @title Constant
 */

@Configuration
@Data
public class ConstantProperties  {

    /**
     * 代码沙箱接口地址
     */
    public static String codesandboxUrl;

    @Value("${codesandbox.url}")
    public void setCodesandboxUrl(String url){
        codesandboxUrl = url;
    }

}
