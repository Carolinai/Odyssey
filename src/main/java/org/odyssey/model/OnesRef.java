package org.odyssey.model;

/** ONES 对象引用（uuid + 名称），用于产品线/产品模块等 */
public class OnesRef {

    private String uuid;
    private String name;

    public OnesRef() {
    }

    public OnesRef(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
