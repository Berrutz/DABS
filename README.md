# SDAI — Distributed Agent-Based System

**A multi-agent platform built with JADE that combines NLP (LLM), Prolog logic programming, and distributed containerized deployment.**

![Java](https://img.shields.io/badge/Java-8-ED8B00?style=flat&logo=openjdk&logoColor=white)
![Prolog](https://img.shields.io/badge/SWI--Prolog-8-E61B23?style=flat&logo=swi-prolog&logoColor=white)
![Node.js](https://img.shields.io/badge/Node.js-18-339933?style=flat&logo=node.js&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat&logo=docker&logoColor=white)
![JADE](https://img.shields.io/badge/JADE-4.6.0-blue?style=flat)

---

## Key Features

- **Natural Language to Prolog** — Users type plain English; an LLM (Mistral 7B via OpenRouter) translates it into Prolog facts or queries
- **Distributed Multi-Agent Architecture** — Five JADE agents communicate via ACL messages across Docker containers
- **Live Prolog Reasoning** — LogicAgent uses SWI-Prolog (JPL bridge) to assert facts and evaluate queries at runtime
- **Web GUI** — Express.js frontend with real-time WebSocket updates for chat-style interaction
- **Health Monitoring** — MonitorAgent performs periodic ping/pong health checks on all registered agents
- **Fault Tolerance** — Agents detect unreachable peers with 60s backoff suppression; the system degrades gracefully
- **Fully Containerized** — Single `docker compose` command spins up the entire platform

---

## Architecture

```
┌────────────┐     WebSocket      ┌──────────────┐
│  Browser   │◄──────────────────►│  UserAgent   │
│ (index.html)│    port 4000      │  (port 5000) │
└────────────┘                    └──────┬───────┘
                                         │ ACL
                                         ▼
                                  ┌──────────────┐    LLM API     ┌───────────────┐
                                  │ ParserAgent  │───────────────►│  OpenRouter   │
                                  │              │   (Mistral 7B) │  (cloud)      │
                                  └──────┬───────┘                └───────────────┘
                                         │ ACL
                                         ▼
┌──────────────┐                  ┌──────────────┐
│ QueryAgent   │◄────────────────►│ LogicAgent   │
│ (port 5001)  │   ACL messages   │ (SWI-Prolog) │
└──────────────┘                  └──────┬───────┘
                                         │
                                         ▼
                                  ┌──────────────┐
                                  │ knowledge.pl │
                                  │ (Prolog KB)  │
                                  └──────────────┘

         ┌───────────────┐
         │ MonitorAgent  │ ── periodic health checks on all agents
         └───────────────┘
```

### Agent Roles

| Agent | Role | Service Type |
|-------|------|-------------|
| **UserAgent** | Receives user input from the web GUI via WebSocket and forwards it to the parser | `user` |
| **ParserAgent** | Calls the LLM to translate natural language into Prolog, routes to LogicAgent | `parser` |
| **LogicAgent** | Executes Prolog queries/facts using SWI-Prolog's JPL bridge, returns results | `logic` |
| **QueryAgent** | Accepts direct queries from the frontend socket and routes them to ParserAgent | `query` |
| **MonitorAgent** | Periodically pings all DF-registered agents and reports health status | `monitor` |

---

## Agent Interaction Flow

### Full System Workflow

All agents start up, register with the JADE DF (Directory Facilitator), and process a user request end-to-end:

![Full agent workflow](Report/images/Workflow.png)

### Fault Tolerance

When the LogicAgent is unavailable, the ParserAgent detects the failure and suppresses retries for 60 seconds:

![Error handling workflow](Report/images/Workflow-2.png)

---

## Screenshots

### Web Interface

The chat-style GUI where users type natural language facts and queries:

![Web GUI](Report/images/User.png)

### JADE Monitor Dashboard

Real-time view of agent status and platform health:

![JADE Monitor](Report/images/GUI-2.png)

### Docker Containers

All services running as isolated containers via Docker Compose:

![Docker Desktop](Report/images/docker.png)

### Agent Logs

<details>
<summary>MonitorAgent — Health check pings</summary>

![MonitorAgent logs](Report/images/w1.png)
</details>

<details>
<summary>UserAgent — Message processing flow</summary>

![UserAgent logs](Report/images/w2.png)
</details>

<details>
<summary>ParserAgent — Startup and DF registration</summary>

![ParserAgent logs](Report/images/w3.png)
</details>

---

## Use Case: Academic Rule Checking

A user wants to build and query a knowledge base about university rules:

1. **Add a fact** (natural language):
   > "Mario is a student"
   → LLM translates to: `student(mario).`
   → LogicAgent asserts it into Prolog KB

2. **Add a rule** (natural language):
   > "Mario studies at Unical"
   → `studies_at(mario, unical).`

3. **Query** (natural language):
   > "Where does Mario study?"
   → LLM translates to: `?- studies_at(mario, X).`
   → LogicAgent evaluates → `X = unical`
   → LLM humanizes the result: *"Mario studies at Unical."*

---

## Quick Start — Single Machine (Local)

> All containers run on the same machine. Uses `docker-compose.local.yml` to override
> networking so agents communicate via Docker-internal hostnames instead of Tailscale IPs.

### Prerequisites

- [Docker](https://www.docker.com/get-started) and Docker Compose
- An [OpenRouter](https://openrouter.ai/) API key

### 1. Clone the repository

```bash
git clone https://github.com/<your-username>/DABS.git
cd DABS
```

### 2. Set your API key

Edit `JADE-bin-4.6.0/jade/docker/.env` and set your [OpenRouter](https://openrouter.ai/) key:

```
OPENROUTER_API_KEY=sk-or-v1-your-actual-key-here
```

### 3. Build and start the main platform

```bash
cd JADE-bin-4.6.0/jade/docker

docker compose -f docker-compose.yml -f docker-compose.local.yml --profile main up -d --build
```

### 4. Start all agents

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile agent up -d
```

### 5. Open the GUI

Navigate to **http://localhost:4000** in your browser.

### 6. Stop everything

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile main --profile agent down
```

---

## Distributed Deployment — Two Machines over Tailscale VPN

> Each machine runs its own Tailscale sidecar. Machine A hosts the JADE main platform
> (DF/AMS + MonitorAgent); Machine B hosts all the JADE agents and the web GUI.

### Prerequisites

- [Tailscale](https://tailscale.com/) installed and authenticated on both machines
- A Tailscale auth key (reusable) from the [Tailscale admin console](https://login.tailscale.com/admin/settings/keys)
- Both machines have Docker and Docker Compose installed
- An [OpenRouter](https://openrouter.ai/) API key

### 1. Clone the repository on both machines

```bash
git clone https://github.com/<your-username>/DABS.git
cd DABS/JADE-bin-4.6.0/jade/docker
```

### 2. Configure `docker/.env` on both machines

Edit `JADE-bin-4.6.0/jade/docker/.env` with the values for your setup:

```env
# Tailscale
TS_AUTHKEY=tskey-auth-<your-reusable-key>
TAILSCALE_HOSTNAME=jade-main          # only relevant on Machine A
TS_EXTRA_ARGS=--advertise-tags=tag:prod --accept-routes

# Network — use the Tailscale IPs of your machines
PUBLIC_HOST=<tailscale-ip-of-this-machine>
MAIN_HOST=<tailscale-ip-of-machine-A>   # agents use this to reach jade-main

# OpenRouter
OPENROUTER_API_KEY=sk-or-v1-your-actual-key-here
```

> `PUBLIC_HOST` must be set to the Tailscale IP of the machine you are running the command on.
> `MAIN_HOST` must always point to Machine A's Tailscale IP.

### 3. Machine A — Start the main platform

```bash
docker compose --profile main up -d --build
```

### 4. Machine B — Start all agents

```bash
docker compose --profile agent up -d
```

### 5. Open the GUI

Navigate to **http://\<tailscale-ip-of-machine-B\>:4000** in your browser.

### 6. Stop everything

On each machine:

```bash
docker compose --profile main --profile agent down
```

---

## Environment Variables

All variables are set in `JADE-bin-4.6.0/jade/docker/.env`.
`docker-compose.local.yml` overrides the networking ones automatically for single-machine use.

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENROUTER_API_KEY` | *(required)* | OpenRouter API key for LLM calls |
| `PUBLIC_HOST` | `jade-main` / service name | Advertised hostname for JADE MTP |
| `MAIN_HOST` | `jade-main` | Address of the main JADE platform (DF/AMS) |
| `PORT` | `1099` | JADE RMI port |
| `HTTP_PORT` | `7778` | JADE HTTP MTP port (main only) |
| `AGENTS` | *(per service)* | Agent class to start, e.g. `user:agents.UserAgent` |
| `UI_AUTOSTART` | `0` | Set to `1` to auto-start the Express.js web server |
| `MAIN_MONITOR_AUTOSTART` | `0` | Set to `1` to auto-start MonitorAgent |
| `QUERY_HOST` | `query` | Hostname of the QueryAgent container |
| `QUERY_PORT` | `5001` | Port for QueryAgent socket |
| `FRONT_HOST` | `user-ui` | Hostname of the frontend container (for result forwarding) |
| `FRONT_PORT` | `5002` | Port for forwarding results to the GUI |
| `TS_AUTHKEY` | *(Tailscale only)* | Tailscale auth key for VPN sidecar |
| `TAILSCALE_HOSTNAME` | `jade-main` | Tailscale hostname advertised by the sidecar |
| `TS_EXTRA_ARGS` | — | Extra flags passed to `tailscale up` |

---

## Setup Notes

> **`docker/.env` is required and not committed.** It holds your OpenRouter API key and
> (for Tailscale deployments) your VPN credentials. See the Distributed Deployment section
> for the full list of variables to set.

> **Always use `docker-compose.local.yml` for single-machine runs.** The base
> `docker-compose.yml` is designed for distributed Tailscale deployments where each container
> runs on a separate machine. Without the local override, agent containers will try to connect
> to Tailscale IPs and fail.

> **No separate requirements file.** All dependencies (Java 8, SWI-Prolog, Node.js 18, npm packages)
> are installed inside the Docker image via the [Dockerfile](JADE-bin-4.6.0/jade/docker/Dockerfile).

---

## Project Structure

```
DABS/
├── JADE-bin-4.6.0/jade/
│   ├── docker/
│   │   ├── Dockerfile                 # Container image (Java 8 + SWI-Prolog + Node.js 18)
│   │   ├── docker-compose.yml         # Base service definitions (main, agents, user-ui)
│   │   ├── docker-compose.local.yml   # Override for single-machine local testing
│   │   ├── .env                       # Secrets & network config (not committed)
│   │   ├── entrypoint.sh              # Container entrypoint (role dispatcher)
│   │   ├── start_main.sh              # Starts JADE main platform
│   │   └── start_agent.sh             # Starts JADE agent container
│   └── web-ui/
│       ├── agents/
│       │   ├── UserAgent.java          # Web GUI ↔ JADE bridge
│       │   ├── ParserAgent.java        # NL → Prolog via LLM
│       │   ├── LogicAgent.java         # Prolog reasoning (JPL)
│       │   └── QueryAgent.java         # Query routing
│       ├── utils/
│       │   ├── LLMService.java         # OpenRouter API client
│       │   └── MonitorAgent.java       # Health monitoring agent
│       ├── kb/
│       │   └── knowledge.pl            # Prolog knowledge base
│       ├── public/
│       │   ├── index.html              # Chat GUI
│       │   ├── monitor.html            # Monitor dashboard
│       │   └── *.css / *.js
│       ├── server.js                   # Express.js web server
│       ├── test/                       # Unit tests
│       └── lib/                        # JARs (JADE, OkHttp, JSON)
└── Report/
    ├── images/                         # Screenshots and workflow diagrams
    └── Report.pdf
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Agent Platform | JADE 4.6.0 (FIPA-compliant MAS) |
| Logic Engine | SWI-Prolog + JPL (Java-Prolog bridge) |
| LLM | Mistral 7B Instruct via OpenRouter |
| Backend | Java 8 (agents), Node.js 18 (web server) |
| Frontend | HTML/CSS/JS with WebSocket |
| HTTP Client | OkHttp 3.14 |
| Containerization | Docker + Docker Compose |
| Networking | TCP sockets, JADE HTTP MTP, WebSocket |

---

## License

![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-red)

This project was developed as part of a Master's course on Distributed Agent-Based Systems at the University of Calabria (Unical).

All Rights Reserved — see [LICENSE](LICENSE) for details.
