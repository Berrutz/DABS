package agents;

import jade.core.Agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.DFService;
import jade.domain.FIPAException;

public class UserAgent extends Agent {
    protected void setup() {

        System.out.println("🟢 UserAgent started");

        // DF registration
        ServiceDescription sd = new ServiceDescription();
        sd.setType("user");
        sd.setName(getLocalName());

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        dfd.addServices(sd);

        System.out.println("✅ UserAgent registered in DF");

        try {
            DFService.register(this, dfd);
            System.out.println("✅ UserAgent registered in DF");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(jade.lang.acl.MessageTemplate.MatchPerformative(ACLMessage.REQUEST));
                if (msg == null) { block(); return; }
                String content = msg.getContent() == null ? "" : msg.getContent().trim().toLowerCase();
                if ("ping".equals(content)) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent("pong");
                    send(reply);
                }
            }
        });

        // Thread socket listener (port 5000)
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(5000, 0, InetAddress.getByName("127.0.0.1"))) {
                System.out.println("🌐 Waiting for messages on port 5000...");
                while (true) {
                    Socket socket = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String input = reader.readLine();

                    if (input != null && !input.trim().isEmpty()) {
                        System.out.println("📨 Message received from the frontend: " + input);

                        addBehaviour(new SendToParserBehaviour(input));
                    }

                    socket.getOutputStream().write("✅ Fact received.\n".getBytes());
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

    }

    private class SendToParserBehaviour extends TickerBehaviour {
        private String input;
        private int attempts = 0;
        private final int maxAttempts = 5;
    
        public SendToParserBehaviour(String input) {
            super(UserAgent.this, 2000); // every 2 seconds
            this.input = input;
        }
    
        @Override
        protected void onTick() {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("parser");
            template.addServices(sd);
    
            try {
                DFAgentDescription[] results = DFService.search(myAgent, template);
                if (results.length > 0) {
                    AID parserAID = results[0].getName();
                    System.out.println("🎯 ParserAgent found: " + parserAID.getLocalName());
    
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(parserAID);
                    msg.setContent(input);
                    send(msg);
                    System.out.println("📤 Sent message to ParserAgent: " + input);
                    stop();
                } else {
                    System.out.println("⌛ ParserAgent not yet available...");
                    attempts++;
                    if (attempts >= maxAttempts) {
                        System.out.println("❌ Unable to contact ParserAgent after several attempts.");
                        notifyFrontend("❌ Error: ParserAgent unavailable. The fact was not processed.");
                        stop();
                    }
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }
    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
            System.out.println("UserAgent deregistered from DF");
        } catch (Exception e) { /* ignore */ }
        super.takeDown();
    }

    // Notify errors to the frontend (reuses channel 5002)
    private void notifyFrontend(String message) {
        String host = System.getenv().getOrDefault("FRONT_HOST", "127.0.0.1");
        int port; try { port = Integer.parseInt(System.getenv().getOrDefault("FRONT_PORT", "5002")); } catch (Exception e) { port = 5002; }
        try (java.net.Socket socket = new java.net.Socket(host, port);
             java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(socket.getOutputStream())) {
            writer.write(message + "\n");
            writer.flush();
            System.out.println("📤 Error notified to the frontend: " + message);
        } catch (java.io.IOException e) {
            System.err.println("❌ Error sending error to the frontend: " + e.getMessage());
        }
    }
}
