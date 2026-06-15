# Real-Time Trade Journey UI Architecture

## 1. Objective

Build a React UI and backend APIs to visualize end-to-end trade journey
across microservices in real time.

------------------------------------------------------------------------

## 2. Key Features

-   Search by tradeId or tradeId + version
-   Visual node-based journey
-   Real-time updates (WebSocket/SSE)
-   Expandable node details
-   Timeline of events
-   Historical + live view

------------------------------------------------------------------------

## 3. Architecture Overview

### Components

-   React UI (React Flow)
-   Trace API Service
-   Data API (existing)
-   Live Update Publisher

------------------------------------------------------------------------

## 4. UI Design

### Sections

-   Search Bar
-   Summary Header
-   Journey Graph
-   Node Details Panel
-   Timeline Panel

### Node Status

-   NOT_STARTED, PROCESSING, SUCCESS, FAILED, PARTIAL

------------------------------------------------------------------------

## 5. API Design

### Get Versions

GET /api/traces/trades/{tradeId}/versions

### Get Trace

GET /api/traces/trades/{tradeId}/versions/{version}

### Get Timeline

GET /api/traces/trades/{tradeId}/versions/{version}/timeline

------------------------------------------------------------------------

## 6. Live Updates

### WebSocket Subscription

{ "action": "SUBSCRIBE_TRACE", "tradeId": "T1", "version": 1 }

### Event Message

{ "type": "TRACE_NODE_UPDATE", "tradeId": "T1", "version": 1, "nodeId":
"funding", "status": "PROCESSING" }

------------------------------------------------------------------------

## 7. Node Model

Each node contains: - nodeId - name - status - timestamp - metrics -
details

------------------------------------------------------------------------

## 8. Suggested Nodes

-   Trade Received
-   Trade Event
-   Trade Consumer
-   SO Created
-   Funding
-   Accounting

------------------------------------------------------------------------

## 9. Implementation Notes

### Phase 1

-   REST APIs
-   Static UI

### Phase 2

-   WebSocket
-   Animation (blinking)

------------------------------------------------------------------------

## 10. GitHub Copilot Prompt

Build a React + Spring Boot system that: - visualizes trade journey -
supports real-time updates - uses React Flow - uses WebSocket for push
updates
