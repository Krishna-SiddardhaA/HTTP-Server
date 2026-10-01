# Use a lightweight Java runtime environment
FROM eclipse-temurin:21-jdk-alpine

# Set the working directory inside the container
WORKDIR /app

# Copy all files from your local folder into the container
COPY . /app

# Compile the raw Java code
RUN javac Main.java

# Expose your custom server port
EXPOSE 4221

# Command to start the server when the container boots
CMD ["java", "Main"]