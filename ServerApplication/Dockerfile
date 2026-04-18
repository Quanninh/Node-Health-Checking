# Use Java 17 runtime
FROM maven:3.9.9-eclipse-temurin-21

# Set working directory
WORKDIR /app

# Copy all files into container
COPY . .

RUN apt-get update && apt-get install -y maven

# Build the Spring Boot app
RUN mvn clean package

# Expose port (Render uses PORT env)
EXPOSE 8080

# Run the jar file
CMD ["sh", "-c", "java -jar target/*.jar"]