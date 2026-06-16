package io.testseer.backend.query.maven;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MavenScopeFilterTest {

    @Test
    void runtime_includesCompileScope() {
        assertThat(MavenScopeFilter.matches("compile", "runtime")).isTrue();
        assertThat(MavenScopeFilter.matches("runtime", "runtime")).isTrue();
        assertThat(MavenScopeFilter.matches("test", "runtime")).isFalse();
    }

    @Test
    void sqlScopes_runtime_expandsToCompileAndRuntime() {
        assertThat(MavenScopeFilter.sqlScopes("runtime")).containsExactly("compile", "runtime");
    }
}
