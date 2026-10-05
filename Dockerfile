# Java 字节码与目标 CPU 无关，编译使用构建机原生平台。
FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && addgroup -g 10001 -S app \
    && adduser -u 10001 -S app -G app \
    && mkdir -p /app/audit \
    && chown app:app /app /app/audit
WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar
USER 10001:10001
ENV TZ=Asia/Shanghai \
    AIO_QUERY_AUDIT_DIRECTORY=/app/audit \
    JAVA_OPTS="-Xms128m -Xmx512m -XX:+UseG1GC"
EXPOSE 45680
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
