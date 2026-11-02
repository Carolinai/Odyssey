# Odyssey 部署镜像：JRE 8 运行已打包的 jar，数据目录挂载为 /data
# 服务器无法访问 Docker Hub/镜像源 CDN，故复用服务器上已有的 jenkins jdk8 镜像作基础（离线可用）
FROM jenkins/jenkins:lts-jdk8

USER root

# 时区与中文编码（容器内日志、文件名中文正常显示）
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /app

# 构建产物：本机 mvn package 后以 app.jar 名称放到构建目录（服务器 ~/odyssey/）
COPY app.jar app.jar

# 数据卷：客户信息/附件/审计/会话等全部落在这里，持久化在宿主机
VOLUME ["/data"]

EXPOSE 8787

ENTRYPOINT ["java", "-jar", "app.jar", "--odyssey.data-dir=/data"]
