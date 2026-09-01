FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY ai-agent-station-core/pom.xml ai-agent-station-core/pom.xml
COPY ai-agent-station-infrastructure/pom.xml ai-agent-station-infrastructure/pom.xml
COPY ai-agent-station-app/pom.xml ai-agent-station-app/pom.xml
RUN mvn -pl ai-agent-station-app -am dependency:go-offline

COPY ai-agent-station-core ai-agent-station-core
COPY ai-agent-station-infrastructure ai-agent-station-infrastructure
COPY ai-agent-station-app ai-agent-station-app
RUN mvn -pl ai-agent-station-app -am -DskipTests package

FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system agent \
    && useradd --system --gid agent --create-home agent

WORKDIR /opt/ai-agent-station
COPY --from=build /workspace/ai-agent-station-app/target/ai-agent-station-app.jar app.jar
USER agent

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/opt/ai-agent-station/app.jar"]
