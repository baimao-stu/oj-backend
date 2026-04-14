# 基础镜像
FROM eclipse-temurin:21-jdk-alpine

# docker 内部的指定工作目录
WORKDIR /app

# 将 jar 包添加到工作目录
COPY ./oj-backend-0.0.1-SNAPSHOT.jar .

# 说明会暴露的端口，实际启动还得自己指
EXPOSE 50000

# 启动命令
ENTRYPOINT ["java","-jar","/app/oj-backend-0.0.1-SNAPSHOT.jar"]
