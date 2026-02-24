# Local Deployment (Single Machine)

All 5 JADE containers run on one machine using Docker-internal DNS names.
No Tailscale, no IP addresses to configure.

---

## Prerequisites

- [Docker](https://www.docker.com/get-started) and Docker Compose
- An [OpenRouter](https://openrouter.ai/) API key

---

## Step 1 — Clone the repository

```bash
git clone https://github.com/<your-username>/DABS.git
cd DABS
```

---

## Step 2 — Create and configure `.env`

If `JADE-bin-4.6.0/jade/docker/.env` does not exist, create it. The only required variable for local mode is your OpenRouter API key:

```env
OPENROUTER_API_KEY=sk-or-v1-your-key-here
```

Replace `sk-or-v1-your-key-here` with your actual [OpenRouter API key](https://openrouter.ai/settings/keys).
All other variables are handled automatically by `docker-compose.local.yml`.

---

## Step 3 — Start the main platform

```bash
cd JADE-bin-4.6.0/jade/docker
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile main up -d --build
```

Wait for the build to complete (first run only; subsequent starts are fast).

---

## Step 4 — Start all agents

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile agent up -d
```

---

## Expected containers (5 total, no Tailscale sidecars)

```
jade-main    user-ui    parser    logic    query
```

Verify with:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}"
```

---

## Step 5 — Open the GUI

Navigate to **http://localhost:4000** in your browser.

You should see the chat interface. Try typing a fact:

> "Mario is a student"

Then query:

> "Is Mario a student?"

---

## Stop everything

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile main --profile agent down
```

---

## How networking works

`docker-compose.local.yml` overrides `PUBLIC_HOST` to the Docker service name for every
container (`jade-main`, `user-ui`, `parser`, `logic`, `query`), and sets `MAIN_HOST` to
`jade-main`. This lets JADE use Docker's internal DNS for all inter-container communication —
no real IP addresses are needed.

Port `4000` is published to the host only by `user-ui` (defined in `docker-compose.local.yml`).
