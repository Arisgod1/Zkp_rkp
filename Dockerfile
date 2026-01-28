# 使用 Eclipse Temurin JDK 21（轻量级）
FROM eclipse-temurin:21-jdk-alpine

# 设置工作目录
WORKDIR /app

# 复制构建好的 JAR 文件
# 注意：需要先执行 mvn clean package 生成 target/*.jar
COPY target/*.jar app.jar

# 暴露端口
EXPOSE 8080

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]