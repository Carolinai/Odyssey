package org.odyssey.store;

/** 环境名重复（name 为搜索主键，必须唯一） */
public class DuplicateEnvironmentException extends RuntimeException {

    public DuplicateEnvironmentException(String name) {
        super("环境已存在：" + name);
    }
}
