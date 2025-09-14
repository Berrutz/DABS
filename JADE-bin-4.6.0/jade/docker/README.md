# Start with Docker Compose

This folder contains the compose setup to run the JADE platform (Main) and the agents (User GUI, Parser, Logic, Query).

## 1) Build image

Open a shell and move here:

```bash
cd Project/JADE-bin-4.6.0/jade/docker
```

Rebuild the base image used by all services:

```bash
docker compose build jade-main
```

Tip when you change Java/Node or scripts: `--no-cache`.

Note: agents now inherit `build:` from the `jade-agent` service,
so the first `docker compose --profile agent up user-ui` will
automatically build the `sdai:main` image if it is not present locally.

## 2) Start Main (DF/AMS + Monitor UI)

```bash
docker compose --profile main up jade-main
```

- JADE Main listens on `1099` (IMTP/RMI) and `7778` (HTTP-MTP).
- The monitor dashboard is at `http://localhost:4100/monitor`.

## 3) Start agents

Start GUI + UserAgent (same container, GUI on port 4000):

```bash
docker compose --profile agent up user-ui
# GUI: http://localhost:4000
```

Start Parser, Logic and Query as dedicated services (they connect to Main):

```bash
docker compose --profile agent up parser  
docker compose --profile agent up logic  
docker compose --profile agent up query
```

### Agents on remote machines

To connect an agent container running on another machine (e.g., via Tailscale),
export the reachable IP via `PUBLIC_HOST` and specify the Main’s IP with `MAIN_HOST`.
Also make sure to expose ports `1099` and `7778` (already declared in compose for `user-ui`).

```bash
PUBLIC_HOST=100.xx.yy.zz MAIN_HOST=100.84.182.11 docker compose --profile agent up user-ui
```

Notes:
- Agent names are automatically made unique (random suffix) to avoid `already-registered` errors.
- The GUI (`user-ui`) sends queries to the `query` service, and the Logic service sends answers back to `user-ui` (configured via env in compose).

## 4) Handling unavailable agents

If an agent is stopped (e.g., container killed) during processing:

- `QueryAgent` retries contacting `ParserAgent` up to 5 times (2s interval). If it fails, it notifies the frontend with an error message via socket on `FRONT_HOST:FRONT_PORT` (default `127.0.0.1:5002`).
- `ParserAgent` retries contacting `LogicAgent` up to 5 times (2s interval). If it fails, it notifies the frontend with an error message on the same channel (`FRONT_HOST:FRONT_PORT`).
- The Monitor dashboard keeps showing status (up/down) and removes agents not reachable anymore after a TTL.

Useful environment variables (already set in compose where needed):

- `FRONT_HOST`: host of the GUI server that receives answers (default `127.0.0.1`).
- `FRONT_PORT`: port of the GUI server that receives answers (default `5002`).
- `QUERY_TIMEOUT_MS`: frontend timeout for LogicAgent answers (default 10000ms).

## 5) Stop / Cleanup

```bash
# stop everything currently running for the used profiles
docker compose --profile agent down
docker compose --profile main  down
```

or 

```bash
docker compose stop logic
```
