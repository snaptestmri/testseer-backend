package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserSemanticTest {

    private final JavaParserService parser = new JavaParserService();

    @Test
    void extractsClassJavadoc() {
        String source = """
                package com.example;
                /**
                 * Handles order creation and lifecycle management.
                 * Coordinates with PaymentService to charge customers.
                 */
                public class OrderService {
                    public void createOrder() {}
                }
                """;
        ParsedModel model = parser.parse("OrderService.java", source);
        assertThat(model.classJavadoc()).contains("order creation");
        assertThat(model.classJavadoc()).contains("PaymentService");
    }

    @Test
    void extractsPublicMethodsWithSignature() {
        String source = """
                package com.example;
                public class RefundProcessor {
                    /**
                     * Processes a full refund. Only valid within 30 days of delivery.
                     */
                    public RefundResult processRefund(String orderId, Money amount)
                            throws RefundWindowExpiredException, PaymentException {
                        return null;
                    }
                    private void internalHelper() {}
                    public String getId() { return null; }
                }
                """;
        ParsedModel model = parser.parse("RefundProcessor.java", source);
        List<ParsedModel.MethodDef> methods = model.publicMethods();
        assertThat(methods).hasSize(1); // getId is a getter — excluded
        ParsedModel.MethodDef m = methods.get(0);
        assertThat(m.name()).isEqualTo("processRefund");
        assertThat(m.javadoc()).contains("30 days");
        assertThat(m.returnType()).isEqualTo("RefundResult");
        assertThat(m.parameterTypes()).containsExactly("String", "Money");
        assertThat(m.thrownExceptions()).containsExactlyInAnyOrder(
                "RefundWindowExpiredException", "PaymentException");
    }

    @Test
    void excludesGettersSettersFromPublicMethods() {
        String source = """
                package com.example;
                public class Order {
                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }
                    public boolean isValid() { return true; }
                    public void cancel(String reason) {}
                    private String id;
                }
                """;
        ParsedModel model = parser.parse("Order.java", source);
        assertThat(model.publicMethods().stream().map(ParsedModel.MethodDef::name))
                .containsExactly("cancel");
    }

    @Test
    void extractsEnumValues() {
        String source = """
                package com.example;
                /**
                 * Represents the lifecycle states of an order.
                 */
                public enum OrderStatus {
                    PENDING, PAYMENT_PROCESSING, CONFIRMED,
                    SHIPPED, DELIVERED, CANCELLED, REFUNDED
                }
                """;
        ParsedModel model = parser.parse("OrderStatus.java", source);
        assertThat(model.enumValues()).containsExactlyInAnyOrder(
                "PENDING", "PAYMENT_PROCESSING", "CONFIRMED",
                "SHIPPED", "DELIVERED", "CANCELLED", "REFUNDED");
        assertThat(model.classJavadoc()).contains("lifecycle states");
    }

    @Test
    void methodWithNoJavadoc_hasNullJavadocField() {
        String source = """
                package com.example;
                public class SimpleService {
                    public void execute(String input) {}
                }
                """;
        ParsedModel model = parser.parse("SimpleService.java", source);
        assertThat(model.publicMethods()).hasSize(1);
        assertThat(model.publicMethods().get(0).javadoc()).isNull();
        assertThat(model.publicMethods().get(0).thrownExceptions()).isEmpty();
    }

    @Test
    void nullOrBlankSource_returnsParseErrorWithoutThrowing() {
        ParsedModel nullContent = parser.parse("Empty.java", null);
        assertThat(nullContent.parseError()).isTrue();
        assertThat(nullContent.parseErrorDetail()).contains("null");

        ParsedModel blankContent = parser.parse("Empty.java", "   ");
        assertThat(blankContent.parseError()).isTrue();
        assertThat(blankContent.parseErrorDetail()).contains("empty");
    }

    @Test
    void syntacticallyInvalidSource_returnsParseError() {
        ParsedModel model = parser.parse("Broken.java", "public class { !!!");
        assertThat(model.parseError()).isTrue();
        assertThat(model.parseErrorDetail()).isNotBlank();
    }
}
