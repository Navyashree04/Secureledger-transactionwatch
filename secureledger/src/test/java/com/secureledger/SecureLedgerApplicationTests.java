package com.secureledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SecureLedgerApplicationTests {

    @Test
    void contextLoads() {
        // Fails fast if wiring is broken (security config, JPA mappings, etc.)
    }
}
