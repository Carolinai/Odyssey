package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一条「到达路径」：从公司侧连进某台目标机器的完整路径。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessEntry {

    /** ssh / rdp / sunlogin(向日葵) / web / ssh-hop */
    private String method;

    /** 目标（如「应用服务器 10.0.1.10」） */
    private String target;

    /** 跳板说明 */
    private String via;

    private Account account;

    /** 向日葵设备 ID */
    private String id;

    /** 向日葵验证码等（不经过 account 结构的独立密码） */
    private String password;

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getVia() {
        return via;
    }

    public void setVia(String via) {
        this.via = via;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
