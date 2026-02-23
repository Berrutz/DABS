
package dummy;

import jade.core.Agent;

public class DummyAgent extends Agent {
    @Override
    protected void setup() {
        System.out.println("DummyAgent " + getLocalName() + " is ready.");
    }

    @Override
    protected void takeDown() {
        System.out.println("DummyAgent " + getLocalName() + " is terminating.");
    }

}