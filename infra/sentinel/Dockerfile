FROM eclipse-temurin:21.0.8_9-jre-jammy
ARG SENTINEL_VERSION=1.8.10
ADD --checksum=sha256:9e0d11bc30006c9f70cde982b456fc7bd03b94ec9ab5c45c034eb66b968ca19e https://github.com/alibaba/Sentinel/releases/download/${SENTINEL_VERSION}/sentinel-dashboard-${SENTINEL_VERSION}.jar /app/sentinel-dashboard.jar
RUN chmod 644 /app/sentinel-dashboard.jar
USER 10001
ENV HOME=/tmp
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -Dserver.port=8080 -Dsentinel.dashboard.auth.username=\"$SENTINEL_USERNAME\" -Dsentinel.dashboard.auth.password=\"$SENTINEL_PASSWORD\" -jar /app/sentinel-dashboard.jar"]
