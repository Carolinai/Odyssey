package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 账号信息：vpn/bastion/access 等处复用。字段均可选。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Account {

    private String user;

    private String password;

    /** OTP 说明（如「需要找张三要动态码」） */
    private String otp;

    /** SSH 私钥路径 */
    private String key;

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
