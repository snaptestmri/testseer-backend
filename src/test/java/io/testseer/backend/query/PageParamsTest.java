package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageParamsTest {

    @Test
    void validate_appliesDefaults() {
        PageParams.Validated page = PageParams.validate(null, null);

        assertThat(page.limit()).isEqualTo(PageParams.DEFAULT_LIMIT);
        assertThat(page.offset()).isZero();
    }

    @Test
    void validate_rejectsInvalidLimit() {
        assertThatThrownBy(() -> PageParams.validate(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> PageParams.validate(PageParams.MAX_LIMIT + 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void validate_rejectsNegativeOffset() {
        assertThatThrownBy(() -> PageParams.validate(10, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }
}
