FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

# --- Слой зависимостей ---
# Копируем только то, что нужно для резолва зависимостей. Пока pom.xml не
# меняется, этот слой берётся из кэша Docker, а зависимости уже скачаны в
# кэш-том /root/.m2 — повторные сборки не качают их заново ("бесконечный build").
COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml .
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline

# --- Слой сборки ---
# Исходники меняются часто, но зависимости уже лежат в кэше выше, поэтому
# пересборка после правки кода — это только компиляция, без докачки.
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
