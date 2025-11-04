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
EOF
```
(sostituisci la key con quella reale; lascia `PUBLIC_HOST` vuoto così lo script userà l’IP Tailscale).

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
