# Avvio JADE via Tailscale (JADE 4.6.0)

Scopo: eseguire il Main JADE sulla macchina **A** (`100.84.182.11`) e collegare da remoto l'interfaccia `user-ui` sulla macchina **B** (`100.96.79.2`). La versione utilizzata è **JADE 4.6.0** (log di bootstrap: `This is JADE 4.6.0 - revision 6869 of 30-11-2022`).

> Nota di rete  
> Per seguire le raccomandazioni del *JADE Programmers Guide* 4.6.0 sui container remoti (`-local-host` e `-local-port` devono pubblicare l'indirizzo raggiungibile dal Main), il servizio `user-ui` condivide lo stack di rete dell'host (`network_mode: host`). Questo richiede un host Linux (una distro WSL va bene) e fa sì che il processo dentro il container possa bindare l'IP Tailscale reale `100.96.79.2`. Senza questa modalità JADE pubblica l'indirizzo del bridge Docker (`172.x.x.x`), il Main prova a connettersi lì e si ottiene l'eccezione `jade.core.IMTPException / No skeleton`.

Prima di iniziare:
- Assicurati che entrambe le macchine siano collegate alla stessa rete Tailscale con gli IP indicati.
- Apri le porte `1099`, `7778`, `4000`, `5000`, `5001`, `5002`, `4100` negli eventuali firewall locali.
- Ricorda che le variabili impostate con `$env:VAR = "..."` durano solo per il terminale corrente.

---

## Macchina A – 100.84.182.11 (JADE Main)

```powershell
Set-Location C:\percorso\al\progetto\JADE-bin-4.6.0\jade\docker
docker compose build jade-main

$env:PUBLIC_HOST = "100.84.182.11"
docker compose --profile main up -d jade-main
```

- Monitor: `http://100.84.182.11:4100/monitor`
- Puoi eventualmente attivare anche `parser`, `logic`, `query` sulla stessa macchina con `docker compose --profile agent up -d parser logic query`.

---

## Macchina B – 100.96.79.2 (user-ui remoto)

```powershell
Set-Location C:\percorso\al\progetto\JADE-bin-4.6.0\jade\docker
docker compose build jade-agent
# usa --no-cache se devi forzare il rebuild dell'immagine
# docker compose build jade-agent --no-cache

$env:PUBLIC_HOST = "100.96.79.2"   # IP Tailscale pubblicato nei riferimenti RMI
$env:MAIN_HOST   = "100.84.182.11" # indirizzo del Main a cui collegarsi
$env:QUERY_HOST  = "100.84.182.11" # la UI invia le query al QueryAgent remoto
$env:LOCAL_HOST  = "100.96.79.2"   # IP su cui il CommandDispatcher (JICP) deve bindare
$env:LOCAL_PORT  = "1099"          # porta locale usata sia dal bind che dall'annuncio

docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent up user-ui
```

- GUI locale: `http://localhost:4000`
- GUI via Tailscale: `http://100.96.79.2:4000`

Per arrestare il container:

```powershell
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent down user-ui
```

---

## Motivo dei parametri `jade.Boot`

All'interno di `start_agent.sh` lo stack viene avviato con:

- `-container` – crea un container secondario che si collega al Main esistente.
- `-host 100.84.182.11` – indirizzo del Main (IP Tailscale della macchina A).
- `-port 1099` – porta JADE IMTP/RMI del Main.
- `-local-host 100.96.79.2` – (quando impostato) pubblica verso il Main l'IP Tailscale reale della macchina B; senza questo, JADE sceglierebbe l'IP del bridge Docker e il Main genererebbe l'errore `DispatcherException`/`No skeleton`.
- `-local-port 1099` – porta su cui il CommandDispatcher accetta le connessioni in ingresso dal Main; deve coincidere con l'esposizione host (`network_mode: host`) e con il mapping Tailscale.
- `-Djava.rmi.server.hostname=100.96.79.2` – forza gli stub RMI (usati dai servizi ausiliari) a pubblicare l'IP Tailscale, evitando riferimenti allo spazio indirizzi interno del container.

La sequenza sopra segue le indicazioni della documentazione JADE 4.6.0: i container remoti devono essere raggiungibili al pair (`local-host`, `local-port`) annunciato durante l'handshake JICP; in caso contrario si riceve il warning `Not possible to launch JADE on a remote host ... Check the -host and -local-host options`.

---

## Troubleshooting rapido
- Da B esegui `Test-NetConnection -ComputerName 100.84.182.11 -Port 1099` (o `nc -vz 100.84.182.11 1099`) per verificare la raggiungibilità del Main.
- Se il container B esce con `No ICP active`, controlla che:
  - `LOCAL_HOST` punti a un IP realmente assegnato all'host (verifica con `tailscale ip -4` sulla macchina B).
  - Il compose sia stato rilanciato dopo il rebuild dell'immagine (`docker compose build jade-agent`).
  - Nessun processo locale stia già usando la porta `1099`.
