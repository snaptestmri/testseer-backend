# P1: Foundation + Database Schema — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold the `testseer-backend` Spring Boot project, wire all infrastructure dependencies, and create all Flyway migrations so the full database schema exists and is verified by a passing integration test.

**Architecture:** Single Maven module, Spring Boot 3.3, Java 21. All database access uses `JdbcClient` (Spring 6.1). Flyway manages schema versioning. Testcontainers provides real Postgres, MongoDB, and Redis for tests — no mocking of infrastructure.

**Tech Stack:** Java 21, Spring Boot 3.3.0, Maven, Flyway 10, PostgreSQL 16, MongoDB 7, Redis 7, Testcontainers 1.19, JUnit 5

---

## File Structure

```
testseer-backend/
├── pom.xml
├── docker-compose.yml
└── src/
    ├── main/
    │   ├── java/io/testseer/backend/
    │   │   └── TestseerBackendApplication.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__service_registry.sql
    │           ├── V2__symbol_facts.sql
    │           ├── V3__outbound_call_facts.sql
    │           ├── V4__peripheral_and_unsupported_facts.sql
    │           ├── V5__analysis_runs.sql
    │           └── V6__graph_schema.sql
    └── test/
        ├── java/io/testseer/backend/
        │   └── DatabaseMigrationTest.java
        └── resources/
            └── application-test.yml
```

---

### Task 1: Maven project structure and `pom.xml`

**Files:**
- Create: `testseer-backend/pom.xml`

- [ ] **Step 1: Create `pom.xml`**

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
        <version>3.3.0</version>
    </parent>

    <groupId>io.testseer</groupId>
    <artifactId>testseer-backend</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
        <javaparser.version>3.25.10</javaparser.version>
        <spring-cloud-gcp.version>5.3.0</spring-cloud-gcp.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.google.cloud</groupId>
                <artifactId>spring-cloud-gcp-dependencies</artifactId>
                <version>${spring-cloud-gcp.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- JDBC (JdbcClient, Spring 6.1) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- Flyway + Postgres -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>

        <!-- MongoDB -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-mongodb</artifactId>
        </dependency>

        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Kafka -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- GCP Pub/Sub -->
        <dependency>
            <groupId>com.google.cloud</groupId>
            <artifactId>spring-cloud-gcp-starter-pubsub</artifactId>
        </dependency>

        <!-- JavaParser -->
        <dependency>
            <groupId>com.github.javaparser</groupId>
            <artifactId>javaparser-symbol-solver-core</artifactId>
            <version>${javaparser.version}</version>
        </dependency>

        <!-- JSON -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mongodb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
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

- [ ] **Step 2: Verify Maven resolves dependencies**

```bash
mvn dependency:resolve -q
```

Expected: BUILD SUCCESS, no unresolved artifacts.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add testseer-backend Maven project with all Phase 1 dependencies"
```

---

### Task 2: Application entry point and `application.yml`

**Files:**
- Create: `src/main/java/io/testseer/backend/TestseerBackendApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`

- [ ] **Step 1: Create `TestseerBackendApplication.java`**

```java
package io.testseer.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TestseerBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestseerBackendApplication.class, args);
    }
}
```

- [ ] **Step 2: Create `src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: testseer-backend
  datasource:
    url: ${POSTGRES_URL:jdbc:postgresql://localhost:5432/testseer}
    username: ${POSTGRES_USER:testseer}
    password: ${POSTGRES_PASS:testseer}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    mongodb:
      uri: ${MONGODB_URI:mongodb://localhost:27017/testseer}
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: io.testseer.backend
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

testseer:
  stale-threshold-minutes: 60

spring.cloud.gcp:
  pubsub:
    project-id: ${GCP_PROJECT_ID:testseer-dev}

logging:
  level:
    io.testseer: INFO
```

- [ ] **Step 3: Create `src/test/resources/application-test.yml`**

```yaml
spring:
  cloud:
    gcp:
      pubsub:
        emulator-host: localhost:8085
  kafka:
    consumer:
      group-id: testseer-test-${random.uuid}
```

- [ ] **Step 4: Verify application compiles**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/ 
git commit -m "feat: add application entry point and configuration"
```

---

### Task 3: Migration V1 — `service_registry`

**Files:**
- Create: `src/main/resources/db/migration/V1__service_registry.sql`

- [ ] **Step 1: Write the failing test** (create this before the migration)

Create `src/test/java/io/testseer/backend/DatabaseMigrationTest.java`:

```java
package io.testseer.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.cloud.gcp.pubsub.emulator-host=localhost:8085",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class DatabaseMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7");

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void serviceRegistryTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'service_registry'
                ORDER BY column_name
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "service_id", "org_id", "repo", "service_name",
                "module_type", "build_tool", "source_roots", "test_roots",
                "owner_team", "enabled", "metadata", "created_at", "updated_at"
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=DatabaseMigrationTest#serviceRegistryTableExists -q
```

Expected: FAIL — `service_registry` does not exist.

- [ ] **Step 3: Create `V1__service_registry.sql`**

```sql
CREATE TABLE service_registry (
    service_id   VARCHAR(255) PRIMARY KEY,
    org_id       VARCHAR(100) NOT NULL,
    repo         VARCHAR(255) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    module_type  VARCHAR(50)  NOT NULL DEFAULT 'service',
    build_tool   VARCHAR(50)  NOT NULL,
    source_roots TEXT[]       NOT NULL DEFAULT '{"src/main/java"}',
    test_roots   TEXT[]       NOT NULL DEFAULT '{"src/test/java"}',
    owner_team   VARCHAR(255),
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    metadata     JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_registry UNIQUE (org_id, repo, service_name)
);
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=DatabaseMigrationTest#serviceRegistryTableExists -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V1__service_registry.sql \
        src/test/java/io/testseer/backend/DatabaseMigrationTest.java
git commit -m "feat: add V1 migration for service_registry table"
```

---

### Task 4: Migration V2 — `symbol_facts`

**Files:**
- Create: `src/main/resources/db/migration/V2__symbol_facts.sql`
- Modify: `src/test/java/io/testseer/backend/DatabaseMigrationTest.java`

- [ ] **Step 1: Add failing test for `symbol_facts`**

Add this method to `DatabaseMigrationTest`:

```java
@Test
void symbolFactsTableExists() {
    List<String> columns = jdbcClient.sql("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'symbol_facts'
            ORDER BY column_name
            """)
            .query(String.class)
            .list();

    assertThat(columns).contains(
            "id", "org_id", "repo", "service_id", "commit_sha",
            "file_path", "symbol_fqn", "symbol_kind", "snapshot_type",
            "attributes", "evidence_source", "confidence",
            "fact_schema_version", "indexed_at"
    );
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=DatabaseMigrationTest#symbolFactsTableExists -q
```

Expected: FAIL — `symbol_facts` does not exist.

- [ ] **Step 3: Create `V2__symbol_facts.sql`**

```sql
CREATE TABLE symbol_facts (
    id                   BIGSERIAL    PRIMARY KEY,
    org_id               VARCHAR(100) NOT NULL,
    repo                 VARCHAR(255) NOT NULL,
    service_id           VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha           VARCHAR(40)  NOT NULL,
    file_path            VARCHAR(500) NOT NULL,
    symbol_fqn           VARCHAR(500) NOT NULL,
    symbol_kind          VARCHAR(50)  NOT NULL,
    snapshot_type        VARCHAR(10)  NOT NULL,
    attributes           JSONB,
    evidence_source      VARCHAR(50)  NOT NULL,
    confidence           FLOAT        NOT NULL DEFAULT 1.0,
    fact_schema_version  VARCHAR(10)  NOT NULL DEFAULT '1.0',
    indexed_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_symbol_facts_service ON symbol_facts(org_id, service_id, commit_sha);
CREATE INDEX idx_symbol_facts_fqn     ON symbol_facts(symbol_fqn);
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=DatabaseMigrationTest#symbolFactsTableExists -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V2__symbol_facts.sql \
        src/test/java/io/testseer/backend/DatabaseMigrationTest.java
git commit -m "feat: add V2 migration for symbol_facts table"
```

---

### Task 5: Migration V3 — `outbound_call_facts`

**Files:**
- Create: `src/main/resources/db/migration/V3__outbound_call_facts.sql`
- Modify: `src/test/java/io/testseer/backend/DatabaseMigrationTest.java`

- [ ] **Step 1: Add failing test**

```java
@Test
void outboundCallFactsTableExists() {
    List<String> columns = jdbcClient.sql("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'outbound_call_facts'
            """)
            .query(String.class)
            .list();

    assertThat(columns).contains(
            "id", "org_id", "repo", "service_id", "commit_sha",
            "source_symbol", "http_method", "path",
            "snapshot_type", "evidence_source", "confidence", "indexed_at"
    );
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=DatabaseMigrationTest#outboundCallFactsTableExists -q
```

Expected: FAIL.

- [ ] **Step 3: Create `V3__outbound_call_facts.sql`**

```sql
CREATE TABLE outbound_call_facts (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    repo            VARCHAR(255) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    source_symbol   VARCHAR(500) NOT NULL,
    http_method     VARCHAR(10),
    path            VARCHAR(500),
    snapshot_type   VARCHAR(10)  NOT NULL,
    evidence_source VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    indexed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbound_service ON outbound_call_facts(org_id, service_id, commit_sha);
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=DatabaseMigrationTest#outboundCallFactsTableExists -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V3__outbound_call_facts.sql \
        src/test/java/io/testseer/backend/DatabaseMigrationTest.java
git commit -m "feat: add V3 migration for outbound_call_facts table"
```

---

### Task 6: Migration V4 — `peripheral_facts` and `unsupported_construct_facts`

**Files:**
- Create: `src/main/resources/db/migration/V4__peripheral_and_unsupported_facts.sql`
- Modify: `src/test/java/io/testseer/backend/DatabaseMigrationTest.java`

- [ ] **Step 1: Add failing tests**

```java
@Test
void peripheralFactsTableExists() {
    List<String> columns = jdbcClient.sql("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'peripheral_facts'
            """)
            .query(String.class)
            .list();

    assertThat(columns).contains(
            "id", "org_id", "service_id", "commit_sha",
            "peripheral_type", "detection_tier", "detection_signals",
            "prerequisite_text", "reason_code", "indexed_at"
    );
}

@Test
void unsupportedConstructFactsTableExists() {
    List<String> columns = jdbcClient.sql("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'unsupported_construct_facts'
            """)
            .query(String.class)
            .list();

    assertThat(columns).contains(
            "id", "org_id", "service_id", "commit_sha",
            "file_path", "reason_code", "detail", "indexed_at"
    );
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest="DatabaseMigrationTest#peripheralFactsTableExists+unsupportedConstructFactsTableExists" -q
```

Expected: Both FAIL.

- [ ] **Step 3: Create `V4__peripheral_and_unsupported_facts.sql`**

```sql
CREATE TABLE peripheral_facts (
    id                BIGSERIAL    PRIMARY KEY,
    org_id            VARCHAR(100) NOT NULL,
    service_id        VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha        VARCHAR(40)  NOT NULL,
    peripheral_type   VARCHAR(100) NOT NULL,
    detection_tier    SMALLINT     NOT NULL CHECK (detection_tier IN (1, 2, 3)),
    detection_signals JSONB        NOT NULL,
    prerequisite_text TEXT         NOT NULL,
    reason_code       VARCHAR(100),
    indexed_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_peripheral_service ON peripheral_facts(org_id, service_id);

CREATE TABLE unsupported_construct_facts (
    id          BIGSERIAL    PRIMARY KEY,
    org_id      VARCHAR(100) NOT NULL,
    service_id  VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha  VARCHAR(40)  NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    detail      TEXT,
    indexed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest="DatabaseMigrationTest#peripheralFactsTableExists+unsupportedConstructFactsTableExists" -q
```

Expected: Both PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V4__peripheral_and_unsupported_facts.sql \
        src/test/java/io/testseer/backend/DatabaseMigrationTest.java
git commit -m "feat: add V4 migration for peripheral_facts and unsupported_construct_facts"
```

---

### Task 7: Migration V5 — `analysis_runs`

**Files:**
- Create: `src/main/resources/db/migration/V5__analysis_runs.sql`
- Modify: `src/test/java/io/testseer/backend/DatabaseMigrationTest.java`

- [ ] **Step 1: Add failing test**

```java
@Test
void analysisRunsTableExists() {
    List<String> columns = jdbcClient.sql("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'analysis_runs'
            """)
            .query(String.class)
            .list();

    assertThat(columns).contains(
            "job_id", "org_id", "service_id", "commit_sha",
            "job_type", "status", "attempt",
            "enqueued_at", "started_at", "completed_at", "error_detail"
    );
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=DatabaseMigrationTest#analysisRunsTableExists -q
```

Expected: FAIL.

- [ ] **Step 3: Create `V5__analysis_runs.sql`**

```sql
CREATE TABLE analysis_runs (
    job_id       VARCHAR(255) PRIMARY KEY,
    org_id       VARCHAR(100) NOT NULL,
    service_id   VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha   VARCHAR(40)  NOT NULL,
    job_type     VARCHAR(20)  NOT NULL CHECK (job_type IN ('PR', 'PUSH', 'NIGHTLY')),
    status       VARCHAR(20)  NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED', 'DLQ')),
    attempt      SMALLINT     NOT NULL DEFAULT 1,
    enqueued_at  TIMESTAMPTZ  NOT NULL,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_detail TEXT
);

CREATE INDEX idx_analysis_runs_service ON analysis_runs(service_id, status);
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=DatabaseMigrationTest#analysisRunsTableExists -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V5__analysis_runs.sql \
        src/test/java/io/testseer/backend/DatabaseMigrationTest.java
git commit -m "feat: add V5 migration for analysis_runs job tracking table"
```

---

### Task 8: Migration V6 — `graph_nodes` and `graph_edges`

**Files:**
- Create: `src/main/resources/db/migration/V6__graph_schema.sql`
- Modify: `src/test/java/io/testseer/backend/DatabaseMigrationTest.java`

- [ ] **Step 1: Add failing tests**

```java
@Test
void graphNodesTableExists() {
    List<String> columns = jdbcClient.sql("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'graph_nodes'
            """)
            .query(String.class)
            .list();

    assertThat(columns).contains(
            "id", "org_id", "repo", "service", "module_type", "node_type", "symbol_fqn"
    );
}

@Test
void graphEdgesTableExists() {
    List<String> columns = jdbcClient.sql("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'graph_edges'
            """)
            .query(String.class)
            .list();

    assertThat(columns).contains(
            "id", "from_node", "to_node", "edge_type", "confidence", "evidence_source"
    );
}

@Test
void graphIndexesExist() {
    List<String> indexes = jdbcClient.sql("""
            SELECT indexname FROM pg_indexes
            WHERE tablename IN ('graph_nodes', 'graph_edges')
            """)
            .query(String.class)
            .list();

    assertThat(indexes).contains(
            "idx_edges_from", "idx_edges_to", "idx_nodes_fqn", "idx_nodes_service"
    );
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest="DatabaseMigrationTest#graphNodesTableExists+graphEdgesTableExists+graphIndexesExist" -q
```

Expected: All three FAIL.

- [ ] **Step 3: Create `V6__graph_schema.sql`**

```sql
CREATE TABLE graph_nodes (
    id          VARCHAR(255) PRIMARY KEY,
    org_id      VARCHAR(100) NOT NULL,
    repo        VARCHAR(255) NOT NULL,
    service     VARCHAR(255) NOT NULL,
    module_type VARCHAR(50)  NOT NULL DEFAULT 'service',
    node_type   VARCHAR(50)  NOT NULL,
    symbol_fqn  VARCHAR(500)
);

CREATE TABLE graph_edges (
    id              BIGSERIAL    PRIMARY KEY,
    from_node       VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    to_node         VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    edge_type       VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    evidence_source VARCHAR(50)
);

CREATE INDEX idx_edges_from    ON graph_edges(from_node, edge_type);
CREATE INDEX idx_edges_to      ON graph_edges(to_node,   edge_type);
CREATE INDEX idx_nodes_fqn     ON graph_nodes(symbol_fqn);
CREATE INDEX idx_nodes_service ON graph_nodes(org_id, service);
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest="DatabaseMigrationTest#graphNodesTableExists+graphEdgesTableExists+graphIndexesExist" -q
```

Expected: All three PASS.

- [ ] **Step 5: Run all migration tests together**

```bash
mvn test -Dtest=DatabaseMigrationTest -q
```

Expected: All 10 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V6__graph_schema.sql \
        src/test/java/io/testseer/backend/DatabaseMigrationTest.java
git commit -m "feat: add V6 migration for graph_nodes and graph_edges with indexes"
```

---

### Task 9: `docker-compose.yml` for local development

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: Create `docker-compose.yml`**

```yaml
version: "3.9"

services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: testseer
      POSTGRES_USER: testseer
      POSTGRES_PASSWORD: testseer
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U testseer"]
      interval: 5s
      timeout: 5s
      retries: 5

  mongodb:
    image: mongo:7
    ports:
      - "27017:27017"
    healthcheck:
      test: ["CMD", "mongosh", "--eval", "db.adminCommand('ping')"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    environment:
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_NODE_ID: 1
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qg==
    ports:
      - "9092:9092"
```

- [ ] **Step 2: Verify containers start**

```bash
docker compose up -d
docker compose ps
```

Expected: All 4 services show `Up` / healthy.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "build: add docker-compose for local development infrastructure"
```

---

### Task 10: Verify full test suite

- [ ] **Step 1: Run all tests**

```bash
mvn test -q
```

Expected: BUILD SUCCESS. All 10 `DatabaseMigrationTest` methods pass. No other test failures.

- [ ] **Step 2: Confirm Flyway migration count**

```bash
docker compose up -d postgres
mvn spring-boot:run &
# wait 10s for startup
curl -s http://localhost:8080/actuator/flyway | python3 -m json.tool
```

Expected: 6 migrations listed, all with `state: SUCCESS`.

Kill the running app: `kill %1`
