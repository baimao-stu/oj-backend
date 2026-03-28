### 1. 在本地maven构建 ###

# 基础镜像
FROM openjdk:8-jdk-alpine

# docker 内部的指定工作目录
WORKDIR /app

# 将 jar 包添加到工作目录，比如 target/oj-backend-0.0.1-SNAPSHOT.jar
#ADD target/oj-backend-0.0.1-SNAPSHOT.jar .
ADD ./oj-backend-0.0.1-SNAPSHOT.jar .
VOLUME ./.mysql-data:/var/lib/mysql

# 说明会暴露的端口，实际启动还得自己指
EXPOSE 50000

# RUN mvn package -DskipTests

# 启动命令
ENTRYPOINT ["java","-jar","/app/oj-backend-0.0.1-SNAPSHOT.jar","--spring.profiles.active=prod"]

### 2. 在容器里maven构建 ###

#FROM maven:3.5-jdk-8-alpine as builder
#
#WORKDIR /app
#COPY pom.xml .
#COPY src ./src
#
#EXPOSE 50000
## 在docker容器里打包
#RUN mvn package -DskipTests
#VOLUME ./.mysql-data:/var/lib/mysql
#
## 启动容器时自动执行的命令，与ENTRYPOINT（更灵活）差不多
#CMD ["java","-jar","/app/oj-backend-0.0.1-SNAPSHOT.jar","--spring.profiles.active=prod"]
