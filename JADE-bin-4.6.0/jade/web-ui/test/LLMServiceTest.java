import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import utils.LLMService;

public class LLMServiceTest {
    @Test
    public void translateToLogicUnknownTypeTriggersError() {
        AtomicReference<String> err = new AtomicReference<>();
        LLMService.translateToLogic("ciao", "invalid", new LLMService.LLMCallback() {
            public void onSuccess(String r) {}
            public void onError(String e) { err.set(e); }
        });
        assertNotNull("Should receive an error", err.get());
        assertTrue(err.get().contains("Tipo di richiesta LLM sconosciuto"));
    }
}
