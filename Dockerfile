FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon -q shadowJar

FROM eclipse-temurin:25-jre AS runner
RUN useradd --system --uid 10001 bot
USER bot
WORKDIR /app
COPY --from=builder /app/build/libs/Cordtrail-*.jar /app/bot.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/bot.jar"]
