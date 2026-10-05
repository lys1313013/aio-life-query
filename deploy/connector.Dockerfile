FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --create-home app
WORKDIR /app
COPY .cache/connector/quarkus-app/ /app/
USER 10001
ENV HASURA_CONFIGURATION_DIRECTORY=/etc/connector
ENV QUARKUS_OTEL_SDK_DISABLED=true
ENV QUARKUS_HTTP_ACCESS_LOG_ENABLED=false
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
