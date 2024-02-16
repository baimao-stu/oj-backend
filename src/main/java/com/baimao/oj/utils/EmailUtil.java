package com.baimao.oj.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author baimao
 * @title EmailUtil
 */
public class EmailUtil {

    /** 验证邮箱格式 */
    public static boolean isEmail(String strEmail) {
        String strPattern = "\\w+([-+.]\\w+)*@\\w+([-.]\\w+)*\\.\\w+([-.]\\w+)*";
        Pattern p = Pattern.compile(strPattern);
        Matcher m = p.matcher(strEmail);
        if (m.matches()) {
            return true;
        } else {
            return false;
        }
    }
}
