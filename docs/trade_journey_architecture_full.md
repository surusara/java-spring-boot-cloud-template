# Architecture Document: Real-Time Trade Journey UI and Trace API

## 1. Objective

Build a production-grade React UI and backend system to visualize
end-to-end trade journey across microservices in real time.

### Goals

-   Trace trade lifecycle across services
-   Provide real-time visibility (blinking nodes)
-   Support debugging and reconciliation
-   Show all versions for a trade
-   Combine historical + live data

------------------------------------------------------------------------

## 2. High-Level Architecture

### Components

1.  React UI (React Flow based)
2.  Trace API Service (Spring Boot)
3.  Data API (existing - DB/recon tables)
4.  Live Update Engine (Kafka → WebSocket)

------------------------------------------------------------------------

## 3. UI Design (Detailed)

### 3.1 Layout

-   Search Bar (tradeId, version optional)
-   Summary Header
-   Graph View (core)
-   Node Details Panel
-   Timeline Panel

### 3.2 Graph Design

Nodes represent business stages: - Trade Received - Trade Event
Published - Trade Consumer - SO Created - Funding - Accounting

Edges represent flow.

### 3.3 Node Status

-   NOT_STARTED
-   RECEIVED
-   PROCESSING
-   SUCCESS
-   FAILED
-   PARTIAL
-   EXCEPTION

### 3.4 Node Structure

{ nodeId, name, status, lastEventAt, blink, metrics, details }

------------------------------------------------------------------------

## 4. API Design (Complete)

### 4.1 Get Versions

GET /api/traces/trades/{tradeId}/versions

Response: { tradeId, versions:\[{version, status, lastUpdatedAt}\] }

------------------------------------------------------------------------

### 4.2 Get Trace

GET /api/traces/trades/{tradeId}/versions/{version}

Response: { tradeId, version, overallStatus, nodes:\[\], edges:\[\],
timeline:\[\] }

------------------------------------------------------------------------

### 4.3 Node Details

GET /api/traces/trades/{tradeId}/versions/{version}/nodes/{nodeId}

------------------------------------------------------------------------

### 4.4 Timeline

GET /api/traces/trades/{tradeId}/versions/{version}/timeline

------------------------------------------------------------------------

## 5. Live Update Design

### 5.1 WebSocket Subscription

{ "action": "SUBSCRIBE_TRACE", "tradeId": "T1", "version": 1 }

### 5.2 Event Payload

{ "type": "TRACE_NODE_UPDATE", "tradeId": "T1", "version": 1, "nodeId":
"funding", "status": "PROCESSING", "timestamp": "...", "blink": true,
"metrics": {...}, "details": {...} }

------------------------------------------------------------------------

## 6. Backend Processing Flow

1.  Consume event (Kafka or DB)
2.  Identify node
3.  Update node state
4.  Update summary
5.  Push event to WebSocket

------------------------------------------------------------------------

## 7. Node Data Requirements

Each node must provide: - status - timestamps - metrics (counts,
duration) - details (outbox, exception, retry) - related IDs (soId,
messageId)

------------------------------------------------------------------------

## 8. Internal Model (Java)

TraceView - tradeId - version - summary - nodes - edges - timeline

NodeView - nodeId - status - metrics - details

------------------------------------------------------------------------

## 9. UI Behavior

### Initial Load

-   Call REST API
-   Render graph

### Live Mode

-   Subscribe WebSocket
-   Update node on event
-   Blink node

------------------------------------------------------------------------

## 10. GitHub Copilot Prompt

Build a React + Spring Boot system: - Graph visualization using React
Flow - REST APIs for trace retrieval - WebSocket for real-time updates -
Node-based journey visualization - Expandable node details - Timeline
view

------------------------------------------------------------------------

## 11. Implementation Phases

### Phase 1

-   REST APIs
-   Static UI

### Phase 2

-   WebSocket
-   Animation

### Phase 3

-   Replay
-   Side-by-side comparison
