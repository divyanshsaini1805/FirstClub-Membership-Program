package com.firstclub.membership;

import org.junit.jupiter.api.Test;

class MembershipApplicationTests {

    /**
     * Pure compile-only smoke test. Spring context tests live in dedicated
     * Testcontainers-backed classes (Phase 10) so unit runs stay fast.
     */
    @Test
    void mainClassLoads() {
        // Reference to ensure the class is on the classpath.
        Class<?> ignored = MembershipApplication.class;
        assert ignored != null;
    }
}
