# =========================
# 1) Build stage
# =========================
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

# 의존성 캐시를 위해 pom 먼저 복사
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline

# 소스 복사 후 빌드
COPY . .
RUN mvn -q -DskipTests package

# =========================
# 2) Run stage
# =========================
FROM eclipse-temurin:17-jre

WORKDIR /app

# jar 복사 (보통 target/*.jar)
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
