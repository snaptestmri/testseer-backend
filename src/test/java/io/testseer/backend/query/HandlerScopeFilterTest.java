package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandlerScopeFilterTest {

    @Test
    void isProductionHandler_rejectsTestSuffixes() {
        assertThat(HandlerScopeFilter.isProductionHandler(
                "com.example.TransactionHistoryServiceTest")).isFalse();
        assertThat(HandlerScopeFilter.isProductionHandler(
                "com.example.UserProfileShoppingHistoryIntTest")).isFalse();
        assertThat(HandlerScopeFilter.isProductionHandler(
                "com.quotient.platform.userprofile.service.TransactionHistoryServiceImpl")).isTrue();
    }

    @Test
    void isTestSourcePath_detectsSrcTestJava() {
        assertThat(HandlerScopeFilter.isTestSourcePath(
                "src/test/java/com/example/FooTest.java")).isTrue();
        assertThat(HandlerScopeFilter.isTestSourcePath(
                "src/main/java/com/example/Foo.java")).isFalse();
    }
}
