# Spring Boot Scaffolding Reference

## Directory Structure

```
{name}/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── src/
│   ├── main/
│   │   ├── java/{package-path}/
│   │   │   ├── Application.java
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── repository/
│   │   │   ├── model/
│   │   │   └── config/
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/{package-path}/
│           └── ApplicationTests.java
├── .gitignore
├── CLAUDE.md
└── README.md
```

**Package derivation:** Convert project name to a Java package. Example: `my-cool-api` → `com.example.mycoolapi`. Ask the user for their preferred group ID (default: `com.example`).

**Maven Wrapper:** Generate `mvnw` and `.mvn/wrapper/` by running:
```bash
cd {path} && mvn wrapper:wrapper -Dmaven=3.9.9
```
If `mvn` is not available, skip the wrapper and note it in the output.

---

## File Templates

### pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.4</version>
        <relativePath/>
    </parent>

    <groupId>{groupId}</groupId>
    <artifactId>{name}</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>{name}</name>
    <description>{description}</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**If the project needs JPA/database:**

Add to dependencies:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

**If the project needs security:**

Add to dependencies:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

### src/main/java/{package-path}/Application.java

```java
package {package};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### src/main/resources/application.yml

```yaml
spring:
  application:
    name: {name}

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### src/test/java/{package-path}/ApplicationTests.java

```java
package {package};

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

### .gitignore

```
# Maven
target/
*.jar
*.war

# IDE
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# OS
.DS_Store
Thumbs.db

# ADE board
/.board/
```

### CLAUDE.md

```markdown
# CLAUDE.md — {name}

## Build Commands

\`\`\`bash
./mvnw compile          # compile
./mvnw test             # run tests
./mvnw spring-boot:run  # start application (http://localhost:8080)
./mvnw package          # build JAR
\`\`\`

## Architecture

Spring Boot 3.x with Java 21, following layered clean architecture.

### Key Conventions

- **Constructor injection** — no field injection (`@Autowired` on fields)
- **`@Valid`** on all request bodies
- **`@Transactional`** for multi-step writes, `@Transactional(readOnly = true)` for reads
- **`@RestControllerAdvice`** for global exception handling
- **Externalize secrets** — use env vars or Spring Cloud Config, not `application.yml`

### Layer Structure

\`\`\`
controller/ → REST endpoints, validation, HTTP concerns
service/    → Business logic, transactions
repository/ → Data access (Spring Data JPA)
model/      → Entities, DTOs, domain types
config/     → Configuration classes
\`\`\`

- Controller → Service → Repository (strict dependency direction)
- Domain models independent of framework annotations where possible
```

### README.md

```markdown
# {name}

{description}

## Getting Started

\`\`\`bash
./mvnw spring-boot:run
\`\`\`

The application starts at http://localhost:8080

## Tech Stack

- Spring Boot 3.x
- Java 21
- Maven
```
