package com.baimao.oj.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户注册请求体，注册时只需给账户和密码即可，头像等可注册完再给

 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 3191241716373120793L;

    private String userAccount;

    private String userPassword;

    private String checkPassword;
}
