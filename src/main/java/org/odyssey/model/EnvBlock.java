package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * 单个环境块（测试/验证/生产之一）的全部连接信息。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EnvBlock {

    private Vpn vpn;

    private Bastion bastion;

    /** 向日葵连接信息（需要向日葵连接的环境使用） */
    private Sunlogin sunlogin;

    /** 服务器清单：app、db、文档服务等，各含 IP 与账号 */
    private List<ClientInfo> clients;

    /** 若干条「到达路径」 */
    private List<AccessEntry> access;

    /** 系统管理员账号 */
    private AdminAccount adminAccount;

    /** 该环境部署的项目代码分支 */
    private List<CodeEntry> code;

    /** 该环境特有的其他信息或备注 */
    private String quirks;

    public Vpn getVpn() {
        return vpn;
    }

    public void setVpn(Vpn vpn) {
        this.vpn = vpn;
    }

    public Bastion getBastion() {
        return bastion;
    }

    public void setBastion(Bastion bastion) {
        this.bastion = bastion;
    }

    public Sunlogin getSunlogin() {
        return sunlogin;
    }

    public void setSunlogin(Sunlogin sunlogin) {
        this.sunlogin = sunlogin;
    }

    public List<ClientInfo> getClients() {
        return clients;
    }

    public void setClients(List<ClientInfo> clients) {
        this.clients = clients;
    }

    public List<AccessEntry> getAccess() {
        return access;
    }

    public void setAccess(List<AccessEntry> access) {
        this.access = access;
    }

    public AdminAccount getAdminAccount() {
        return adminAccount;
    }

    public void setAdminAccount(AdminAccount adminAccount) {
        this.adminAccount = adminAccount;
    }

    public List<CodeEntry> getCode() {
        return code;
    }

    public void setCode(List<CodeEntry> code) {
        this.code = code;
    }

    public String getQuirks() {
        return quirks;
    }

    public void setQuirks(String quirks) {
        this.quirks = quirks;
    }
}
