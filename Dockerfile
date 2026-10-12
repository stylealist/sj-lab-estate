FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# 빌드된 JAR 파일을 복사 (먼저 mvnw.cmd clean package 필요)
COPY target/sj-lab-estate.jar /app/sj-lab-estate.jar

# 활성 프로파일과 DB 접속 정보(ESTATE_DB_*)는 helm/Secret 에서 주입
ENTRYPOINT ["java", "-jar", "/app/sj-lab-estate.jar"]
