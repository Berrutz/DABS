# Distributed Deployment (Two Machines over Tailscale VPN)

Machine A hosts the JADE main platform (DF/AMS + MonitorAgent).
Machine B hosts all JADE agents and the web GUI.
Both machines connect over a Tailscale VPN — no port forwarding or public IPs needed.

---

## Architecture

```
  Machine A                              Machine B
  ─────────────────────────────          ─────────────────────────────────────────
  jade-main-tailscale (sidecar)          jade-agent-tailscale (sidecar)
  │  network namespace                   │  network namespace (shared by all agents)
  └─ jade-main                           ├─ jade-agent
     (JADE DF/AMS + MonitorAgent)        ├─ user-ui  (web GUI, port 4000)
                                         ├─ parser
                                         ├─ logic
                                         └─ query

         ◄────────── Tailscale VPN tunnel ──────────►
         Machine A Tailscale IP: 100.x.y.z
         Machine B Tailscale IP: 100.a.b.c
```

**Key point:** Every agent on Machine B shares `jade-agent-tailscale`'s network namespace.
They communicate with each other via `localhost`, not Docker DNS names.
They reach `jade-main` on Machine A via the Tailscale VPN using `MAIN_HOST`.

---

## Prerequisites

- A [Tailscale](https://tailscale.com/) account with a **reusable auth key** from the
  [Tailscale admin console](https://login.tailscale.com/admin/settings/keys)
- [Docker](https://www.docker.com/get-started) and Docker Compose on **both** machines
- An [OpenRouter](https://openrouter.ai/) API key
- The repository cloned on both machines

---

## Step 1 — Clone the repository on both machines

```bash
git clone https://github.com/<your-username>/DABS.git
cd DABS/JADE-bin-4.6.0/jade/docker
```

---

## Step 2 — Find your Tailscale IPs

On each machine, run:

```bash
tailscale ip -4
```

Note the output — you will need both IPs in the next step.

```
Machine A: 100.x.y.z
Machine B: 100.a.b.c
```

---

## Step 3 — Create and configure `.env` on each machine

If `JADE-bin-4.6.0/jade/docker/.env` does not exist on a machine, create it.
Fill it with the values for **that machine** as shown below.

### Machine A `.env`

```env
PUBLIC_HOST=100.x.y.z          # this machine's Tailscale IP
MAIN_HOST=100.x.y.z            # same — jade-main runs here
TS_AUTHKEY=tskey-auth-...
TAILSCALE_HOSTNAME=jade-main
TS_EXTRA_ARGS=--advertise-tags=tag:prod --accept-routes
OPENROUTER_API_KEY=sk-or-v1-...
```

### Machine B `.env`

```env
PUBLIC_HOST=100.a.b.c          # this machine's Tailscale IP
MAIN_HOST=100.x.y.z            # Machine A's Tailscale IP  ← critical
TS_AUTHKEY=tskey-auth-...      # can reuse the same key or generate a second one
TAILSCALE_HOSTNAME=jade-agent
TS_EXTRA_ARGS=
OPENROUTER_API_KEY=sk-or-v1-...
```

> **Rule:**
> `PUBLIC_HOST` = Tailscale IP of **the machine you are currently configuring**
> `MAIN_HOST`   = Tailscale IP of **Machine A** (always, on both machines)

---

## Step 4 — Machine A: start the main platform

```bash
cd JADE-bin-4.6.0/jade/docker
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile main up -d --build
```

Expected containers (2):

```
jade-main-tailscale    jade-main
```

Wait until `jade-main` logs show the JADE platform started and MonitorAgent registered.

---

## Step 5 — Machine B: start all agents

```bash
cd JADE-bin-4.6.0/jade/docker
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent up -d
```

Expected containers (6):

```
jade-agent-tailscale    jade-agent    user-ui    parser    logic    query
```

---

## Step 6 — Open the GUI

Navigate to **http://\<Machine B Tailscale IP\>:4000** in your browser.

Example: `http://100.a.b.c:4000`

Port `4000` is published on `jade-agent-tailscale` (not on `user-ui` directly), because
`user-ui` shares the sidecar's network namespace.

---

## Stop everything

On each machine:

```bash
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile main --profile agent down
```

---

## How sidecar networking works

- `jade-main` runs inside `jade-main-tailscale`'s network namespace → it gets Machine A's
  Tailscale IP and is reachable at `100.x.y.z:1099` (JADE RMI) and `100.x.y.z:7778` (HTTP MTP)
- All agent containers run inside `jade-agent-tailscale`'s namespace → they share Machine B's
  Tailscale IP and communicate with each other via `localhost`
- `MAIN_HOST` tells agents how to reach `jade-main` over the VPN

---

## Troubleshooting

**"Cannot connect to jade-main"**
Verify `MAIN_HOST` on Machine B matches the output of `tailscale ip -4` on Machine A.
Also confirm `jade-main` is up and the Tailscale VPN tunnel is established (`tailscale status`).

**"Connection refused on port 4000"**
The port is published on `jade-agent-tailscale`, not on `user-ui`. Make sure you are
accessing `http://<Machine B Tailscale IP>:4000`, not `localhost:4000` from Machine A.

**"Agent name already registered" / DF conflict**
Each agent container generates a unique name suffix (hostname + random). If you restart
without `down`ing first, stale DF registrations may conflict. Run `down` before restarting.

**Tailscale auth key expired**
Reusable keys expire after 90 days by default. Generate a new one from the
[Tailscale admin console](https://login.tailscale.com/admin/settings/keys) and update `.env`.
