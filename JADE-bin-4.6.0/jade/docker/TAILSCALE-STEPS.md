# Avvio rapido JADE via Tailscale

Obiettivo: far girare `jade-main` sulla macchina **A** (`100.84.182.11`) e collegare da remoto `user-ui` sulla macchina **B** (`100.96.79.2`).

Prima di iniziare:
- Entrambe le macchine devono essere connesse a Tailscale con gli IP sopra indicati.
- Apri le porte `1099`, `7778`, `4000`, `5000`, `5001`, `5002`, `4100` nei firewall locali.
- In ogni terminale PowerShell i comandi `Set-Location` / `$env:VAR = "..."` vanno riaffermati se riapri la sessione.

---

## Macchina A – 100.84.182.11 (JADE Main)

```powershell
Set-Location C:\percorso\al\progetto\JADE-bin-4.6.0\jade\docker
docker compose build jade-main

$env:PUBLIC_HOST = "100.84.182.11"
docker compose --profile main up -d jade-main
```

- Verifica: `http://100.84.182.11:4100/monitor`

Puoi lanciare anche gli altri agenti locali (parser, logic, query) se necessario, ma non è obbligatorio per collegare solo `user-ui`.

---

## Macchina B – 100.96.79.2 (user-ui remoto)

```powershell
Set-Location C:\percorso\al\progetto\JADE-bin-4.6.0\jade\docker
docker compose build jade-agent
# oppure aggiungi --no-cache se vuoi forzare un rebuild completo
# docker compose build jade-agent --no-cache

$env:PUBLIC_HOST = "100.96.79.2"
$env:MAIN_HOST   = "100.84.182.11"
$env:QUERY_HOST  = "100.84.182.11"
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent up -d user-ui
```

- GUI locale: `http://localhost:4000`
- Accesso via Tailscale: `http://100.96.79.2:4000`

Quando vuoi fermarlo:

```powershell
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --profile agent down user-ui
```

---

Se la UI non vede il Main:
- controlla che il container `jade-main` esponga `1099/7778`;
- verifica da B: `Test-NetConnection -ComputerName 100.84.182.11 -Port 1099`;
- assicurati che `$env:MAIN_HOST` e `$env:QUERY_HOST` siano settati prima del `docker compose up`.
