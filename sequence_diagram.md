%% This generates the sequence diagram shown in the docs, using https://github.com/knsv/mermaid

sequenceDiagram
participant Desktop Server
Note left of Desktop Server: App running in desktop, tied to subdomain.
participant Backend A
Note over Backend A: Identified with backend_id
participant DB
participant Backend B
participant Client
Note over Client: Browser pointing to subdomain

Desktop Server->> Backend A: Establish permanent signaling channel
Backend A->> DB: Map subdomain->backend_id
Client->> Backend B: Request file
Backend B->> Backend B: Create enumerator identified with stream_id
Backend B->> DB: ask for backend_id for subdomain
DB->> Backend B: backend_id
Backend B->> Backend A: Call WS at backend_id asking to send file to Backend B, stream_id (from subdomain)
Backend A->> Desktop Server: Ask To send file to Backend B, stream_id
Desktop Server->> Backend B: Send file to Backend B, stream_id
Backend B->> Client: Send file feeding enumerator stream_id
