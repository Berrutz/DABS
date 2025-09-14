#!/bin/bash

JADE_CP="lib/*:."

echo "[🧹 Pulizia vecchi .class...]"
find agents/ utils/ -name "*.class" -delete

echo "[Compilazione agenti Java...]"
javac -cp "$JADE_CP" agents/*.java utils/*.java --release 8

# Funzione per aprire nuovi terminali
launch_terminal() {
    gnome-terminal -- bash -c "$1; exec bash"
}

echo "[Avvio Main Container SENZA GUI...]"
launch_terminal "java -cp '$JADE_CP' jade.Boot -name CoordinatorPlatform"

sleep 2

echo "[Avvio UserAgent...]"
launch_terminal "java -cp '$JADE_CP' jade.Boot -container -host 127.0.0.1 -container-name UserContainer -agents user:agents.UserAgent"

sleep 1

echo "[Avvio ParserAgent...]"
launch_terminal "java -cp '$JADE_CP' jade.Boot -container -host 127.0.0.1 -container-name ParserContainer -agents parser:agents.ParserAgent"

sleep 1

echo "[Avvio LogicAgent...]"
launch_terminal "java -cp '$JADE_CP' jade.Boot -container -host 127.0.0.1 -container-name LogicContainer -agents logic:agents.LogicAgent"

