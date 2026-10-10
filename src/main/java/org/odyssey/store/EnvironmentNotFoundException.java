package org.odyssey.store;

/** 环境不存在 */
public class EnvironmentNotFoundException extends RuntimeException {

    public EnvironmentNotFoundException(String name) {
        super("环境不存在：" + name);
    }
}
