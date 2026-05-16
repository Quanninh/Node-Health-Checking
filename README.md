# Node-Health-Checking

# Running the Project with PostgreSQL

## 1. Install PostgreSQL

Install PostgreSQL on your machine.

### macOS (Homebrew)

```bash
brew install postgresql
brew services start postgresql
```

### Verify installation

```bash
psql --version
```

---

## 2. Create the Database

Open PostgreSQL:

```bash
psql postgres
```

Create the database:

```sql
CREATE DATABASE dist_sys;
```

---

## 3. Create Database User and Grant Permissions

Inside the PSQL tool, run:

```sql
CREATE USER nodes_health WITH PASSWORD 'yourPass';

GRANT ALL PRIVILEGES
ON DATABASE dist_sys
TO nodes_health;
```

Connect to the database:

```sql
\c dist_sys
```

Grant schema permissions:

```sql
GRANT ALL ON SCHEMA public TO nodes_health;
```

---

## 4. Configure Environment Variables

Take a look at:

```text
.env.example
```

Copy it and create a new file:

```text
.env
```

inside the project root directory:

```text
Node-Health-Checking/
```

---

## 5. Fill in Your Database Credentials

Example `.env` file:

```env
DB_URL=jdbc:postgresql://localhost:5432/dist_sys
DB_USERNAME=nodes_health
DB_PASSWORD=yourPass
```

---

## 6. Run the Spring Boot Server

```bash
mvn spring-boot:run
```

If everything is configured correctly, Spring Boot will automatically:

- connect to PostgreSQL
- create/update tables
- start the backend server

---

## 7. Verify Database Connection

Inside PSQL:

```sql
\c dist_sys
```

Show tables:

```sql
\dt
```

You should see tables similar to:

```text
node
node_history
```

---

## Workflow
```
[Many Computers (Nodes)]
        ↓
  send data (CPU, Memory)
        ↓
[Central Server]
        ↓
  process + store data
        ↓
[GUI Dashboard]
        ↓
  show status to users
```


## Directory structure
```
monitoring-system/monitoring-system/
│
├── ServerApplication/
│   ├── src/main/java/com/monitoring/
│   │   ├── ServerApplication.java
│   │   │
│   │   ├── controller/
│   │   │   └── NodeController.java
│   │   │
│   │   ├── service/
│   │   │   └── NodeService.java
│   │   │
│   │   ├── repository/
│   │   │   └── NodeRepository.java
│   │   │   └── NodeHistoryRepository.java
│   │   ├── model/
│   │   │   ├── Node.java
│   │   │   └── NodeHistory.java
│   │   │
│   │   └── config/
│   │       └── WebConfig.java
│   │
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── data.sql
│   │
│   └── pom.xml
│
├── node-agent/
│   └── NodeAgent.java
│
└── dashboard/
    └── index.html
```

## Web service for running server on the cloud
https://node-health-checking-10.onrender.com


