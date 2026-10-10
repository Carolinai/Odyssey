package org.odyssey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置项。
 */
@ConfigurationProperties(prefix = "odyssey")
public class OdysseyProperties {

    /**
     * 数据根目录：内含 data/（各环境 YAML）与 keys/（RSA 私钥）。
     * 默认 ~/.odyssey，将来可指向部门 git 仓库的本地克隆。
     */
    private String dataDir = System.getProperty("user.home") + "/.odyssey";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
