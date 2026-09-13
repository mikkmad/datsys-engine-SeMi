package datasys.semi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import datasys.semi.engine.*;

class EngineTest {
    @Test
    void teamName() {
        assertEquals("Team SeMi", new Engine().teamName());
    }
}
