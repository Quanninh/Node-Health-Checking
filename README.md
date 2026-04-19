# Node-Health-Checking

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
├── server/
│   ├── src/main/java/com/monitoring/
│   │   ├── MonitoringApplication.java
│   │   │
│   │   ├── controller/
│   │   │   └── NodeController.java
│   │   │
│   │   ├── service/
│   │   │   └── NodeService.java
│   │   │
│   │   ├── repository/
│   │   │   └── NodeRepository.java
│   │   │
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
https://node-health-checking-9.onrender.com

