// gui/server.js
const express = require('express');
const path = require('path');
const { exec } = require('child_process');

const app = express();
const PORT = 4000;
const QUERY_HOST = process.env.QUERY_HOST || '127.0.0.1';
const QUERY_PORT = parseInt(process.env.QUERY_PORT || '5001', 10);
const RESULT_TIMEOUT_MS = parseInt(process.env.RESULT_TIMEOUT_MS || process.env.QUERY_TIMEOUT_MS || '10000', 10);

// Silence logs during tests to avoid breaking the TAP parser
const isTestEnv = process.env.NODE_ENV === 'test';
const log = (...args) => { if (!isTestEnv) console.log(...args); };
const error = (...args) => { if (!isTestEnv) console.error(...args); };

app.use(express.json()); // JSON body parsing middleware
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public'))); // Serve static files from /public

app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public/index.html'));
});

///////////////////////////////////////////////////////////////

app.post("/start-agents", (req, res) => {
    const { spawn } = require("child_process");
    const scriptPath = path.join(__dirname, "run_local_tmux.sh");
  
    log(`🚀 Starting script: ${scriptPath}`);
  
    const subprocess = spawn("bash", [scriptPath], {
      cwd: __dirname,
      stdio: "inherit", // <-- Show everything in the Docker terminal
    });

    // When the script starts correctly, reply to the client
    subprocess.on("spawn", () => {
      log("🟢 Script started successfully.");
      res.status(200).send("✅ Multi-agent system started.");
    });
  
    subprocess.on("error", (err) => {
      error(`❌ exec() error: ${err.message}`);
      res.status(500).send("Error starting the multi-agent system.");
    });
  
    subprocess.on("exit", (code) => {
      log(`ℹ️ Agents process exited with code ${code}`);
    });
  });


//////////////////////////////////////////////////////////////

// Send input to the UserAgent
app.post('/send-fact', (req, res) => {
    const net = require('net');
    const fact = req.body.fact;
    log(`📨 Request received from client: ${fact}`);

    const client = new net.Socket();
    client.connect(5000, '127.0.0.1', () => {
        log('🔌 Connection to UserAgent established');
        client.write(fact + '\n'); 
        client.end();
        // prepare response area and timeout on GUI side
        latestAnswer = "⏳ Waiting for confirmation...";
        if (pendingTimer) { clearTimeout(pendingTimer); pendingTimer = null; }
        pendingTimer = setTimeout(() => {
          latestAnswer = "❌ Error: No confirmation from the system (timeout).";
          pendingTimer = null;
          log("⏱️ Confirmation timeout");
        }, RESULT_TIMEOUT_MS);
        res.status(200).send('Done');
    });

    client.on('error', (err) => {
        error("❌ Connection error to UserAgent:", err.message);
        if (!res.headersSent) {
            res.status(500).send("Error communicating with the agent.");
        }
    });

    client.on('close', () => {
        log('🔒 Connection to UserAgent closed');
    });
});


/////////////////////////////////////////////////////////////

app.post('/send-query', (req, res) => {
    const net = require('net');
    const query = req.body.message;
    log("🔎 Query received:", query);

    latestAnswer = "⏳ Waiting for a response..."; // reset previous answer
    if (pendingTimer) { clearTimeout(pendingTimer); pendingTimer = null; }
    pendingTimer = setTimeout(() => {
      latestAnswer = "❌ Error: No response from LogicAgent (timeout).";
      pendingTimer = null;
      log("⏱️ LogicAgent response timeout");
    }, RESULT_TIMEOUT_MS);

    const client = new net.Socket();
    client.connect(QUERY_PORT, QUERY_HOST, () => {
        log('🔌 Connection to QueryAgent established');
        client.write(query + '\n');
        client.end();
        res.status(200).send("Query sent to the multi-agent system.");
    });

    client.on('error', (err) => {
        error("❌ Connection error to QueryAgent:", err.message);
        if (!res.headersSent) {
            res.status(500).send("Error communicating with QueryAgent.");
        }
    });

    client.on('close', () => {
        log('🔒 Connection to QueryAgent closed');
    });
});


app.get('/get-query-result', (req, res) => {
  res.json({ answer: latestAnswer });
});


const net = require("net");

let latestAnswer = "⏳ Waiting for a response...";
let pendingTimer = null;

// 🔁 Socket to receive the response from LogicAgent
const resultServer = net.createServer((socket) => {
  socket.on("data", (data) => {
    const result = data.toString().trim();
    log("📥 Answer received from LogicAgent:", result);
    latestAnswer = result;
    if (pendingTimer) { clearTimeout(pendingTimer); pendingTimer = null; }
  });

  socket.on("error", (err) => {
    error("❌ Error on result socket:", err.message);
  });
});


resultServer.on("error", (err) => {
  error("❌ Error on result server:", err.message);
});


if (require.main === module) {
  resultServer.listen(5002, () => {
    log("🟢 Listening on port 5002 for answers from LogicAgent");
  });

  //////////////////////// Avvio del server ///////////////////////
  app.listen(PORT, () => {
    log(`🌐 GUI server started at http://localhost:${PORT}`);
  });
}


module.exports = { app, resultServer, setLatestAnswer: (ans) => { latestAnswer = ans; } };

