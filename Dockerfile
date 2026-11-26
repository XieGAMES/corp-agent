# --- Сборка: JDK 21 и Maven внутри образа, поэтому JAVA_HOME машины не важен ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Отдельный слой с зависимостями: пересобирается только при изменении pom.xml
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
# Тестов в проекте нет; результат — target/rag-agent-<версия>.jar
RUN mvn -B package -DskipTests

# --- Запуск: только JRE 21 и собранный jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/rag-agent-*.jar app.jar

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
