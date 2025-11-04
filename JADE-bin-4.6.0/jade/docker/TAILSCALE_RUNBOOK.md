## Tailscale + JADE Main

Prerequisiti:
- essere nella cartella `JADE-bin-4.6.0/jade/docker`
- avere `docker compose` installato
- avere una auth key Tailscale (`tskey-…`)

### 1. Configurazione
```bash
cat <<'EOF' > .env
TS_AUTHKEY=tskey-XXXXXXXXXXXXXXXXXXXXXXXX
TAILSCALE_HOSTNAME=jade-main
PUBLIC_HOST=
MAIN_HOST=
EOF
```
(sostituisci la key con quella reale; lascia `PUBLIC_HOST` vuoto così lo script userà l’IP Tailscale. `MAIN_HOST` servirà solo sulle macchine che eseguiranno gli agenti.)

### 2. Avvio della sidecar Tailscale
```bash
docker compose --profile main up tailscale
```

### 3. Login su Tailscale
```bash
docker exec jade-main-tailscale tailscale up \
  --authkey "$TS_AUTHKEY" $TS_EXTRA_ARGS \
  --hostname "${TAILSCALE_HOSTNAME:-jade-main}"
```
Controllo (opzionale):
```bash
docker exec jade-main-tailscale tailscale status
docker exec jade-main-tailscale tailscale ip -4
```

### 4. Avvio del Main JADE
```bash
docker compose --profile main up -d jade-main
```

Verifica che lo script abbia preso l’IP tailnet:
```bash
docker logs jade-main | grep PUBLIC_HOST
```

### 5. Stop completo
```bash
docker compose --profile main down
```


## Tailscale + JADE Agents (UserUI, Parser, …)

Prerequisiti aggiuntivi (macchina B):
- `MAIN_HOST` deve puntare all’IP Tailscale della macchina A (es. `100.111.x.y`).


### 1. Configurazione
Su macchina B crea/aggiorna `.env`:
```bash
cat <<'EOF' > .env
AGENT_TS_AUTHKEY=tskey-XXXXXXXXXXXXXXXXXXXXXXXX
AGENT_TAILSCALE_HOSTNAME=jade-agent
MAIN_HOST=100.111.85.92          # IP Tailscale del Main
QUERY_HOST=100.111.85.92          # se l'UI deve contattare Query remoto
LOCAL_PORT=2000                   # porta callback RMI locale (opzionale)
EOF
```

### 2. Avvio sidecar Tailscale per gli agenti
```bash
docker compose --profile agent up tailscale-agent
```

### 3. Login sul tailnet
```bash
docker exec jade-agent-tailscale tailscale up \
  --authkey "$AGENT_TS_AUTHKEY" $AGENT_TS_EXTRA_ARGS \
  --hostname "${AGENT_TAILSCALE_HOSTNAME:-jade-agent}"
```
Controlli rapidi:
```bash
docker exec jade-agent-tailscale tailscale status
docker exec jade-agent-tailscale tailscale ip -4
```

### 4. Avviare gli agenti desiderati
Esempio User UI + UserAgent:
```bash
docker compose --profile agent up user-ui
```
Per Parser / Logic / Query:
```bash
docker compose --profile agent up parser
docker compose --profile agent up logic
docker compose --profile agent up query
```

Gli script nel container rilevano automaticamente l’IP Tailscale e impostano `-local-host`/`-local-port` per i callback JADE. Usa `LOCAL_PORT` nell’`.env` se vuoi fissare un valore diverso da `2000`.

### 5. Stop
```bash
docker compose --profile agent down
```
