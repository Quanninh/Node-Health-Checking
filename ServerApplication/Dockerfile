# ===== Build stage =====
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy only project folder
COPY . . 

RUN mvn clean package -DskipTests


# ===== Run stage =====
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copy built jar (correct nested path)
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]