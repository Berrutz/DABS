# Avvio JADE via Tailscale (host Linux)

Requisiti:
- Tailscale già attivo **sull'host** (fuori da Docker). Recupera l'IP con `tailscale ip -4`.
- Docker Engine su Linux (o WSL2 con accesso host). I container usano `network_mode: host`.

## Macchina A – Main JADE
```bash
cd /percorso/al/progetto/JADE-bin-4.6.0/jade/docker
docker compose build jade-main               # una volta sola
export PUBLIC_HOST=$(tailscale ip -4)        # IP tailscale della macchina A
docker compose --profile main up -d jade-main
```
Monitor disponibile su `http://$PUBLIC_HOST:4100/monitor`.

Per fermare: `docker compose --profile main down jade-main`.

## Macchina B – user-ui remoto
```bash
cd /percorso/al/progetto/JADE-bin-4.6.0/jade/docker
docker compose build jade-agent              # una volta sola
export PUBLIC_HOST=$(tailscale ip -4)        # IP tailscale della macchina B
export MAIN_HOST=<IP tailscale macchina A>
export QUERY_HOST=$MAIN_HOST
export LOCAL_HOST=$PUBLIC_HOST
export LOCAL_PORT=1099
docker compose --profile agent up user-ui
```
GUI: `http://localhost:4000` (oppure `http://$PUBLIC_HOST:4000` via Tailscale).

Per fermare: `docker compose --profile agent down user-ui`.

## Check rapidi
- Da B: `nc -vz $MAIN_HOST 1099` verifica la porta del Main.
- Da A (mentre l'agente gira): `nc -vz $PUBLIC_HOST 1099` controlla il callback.
- Se compaiono errori `No ICP active`, ricontrolla che `LOCAL_HOST` corrisponda all'IP Tailscale reale (`tailscale ip -4`) e che la porta 1099 sia libera sull'host.
