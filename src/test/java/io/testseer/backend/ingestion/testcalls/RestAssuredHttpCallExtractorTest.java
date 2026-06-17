package io.testseer.backend.ingestion.testcalls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestAssuredHttpCallExtractorTest {

    @Test
    void extractFromFile_literalAndConstantPaths() {
        String apiConstant = """
                public class APIConstant {
                    public static final String OFFER_REDEEM = "/offers/redeem";
                    public static final String USER_ACCT_LINKED = "/user/acct/linked";
                }
                """;
        String helper = """
                RestAssuredDiagnostics.post(linkRequest, APIConstant.USER_ACCT_LINKED, 200);
                apiHelper.restAPIRequest(Constants.GATEWAY, null, APIConstant.OFFER_REDEEM, null, null, body, "POST", null, 200, null, String.class);
                RestAssured.given().post("/offers/all");
                """;

        Map<String, String> constants = RestAssuredHttpCallExtractor.extractPathConstants(
                List.of(apiConstant, helper));
        List<RestAssuredHttpCallExtractor.ExtractedCall> calls =
                RestAssuredHttpCallExtractor.extractFromFile(helper, constants);

        assertThat(constants).containsEntry("OFFER_REDEEM", "/offers/redeem");
        assertThat(calls).extracting(RestAssuredHttpCallExtractor.ExtractedCall::pathNormalized)
                .contains("/user/acct/linked", "/offers/redeem", "/offers/all");
        assertThat(calls).extracting(RestAssuredHttpCallExtractor.ExtractedCall::httpMethod)
                .contains("POST");
    }

    @Test
    void normalizeTestPath_replacesPathParams() {
        assertThat(RestAssuredHttpCallExtractor.normalizeTestPath("/offer/{offerId}"))
                .isEqualTo("/offer/{*}");
    }
}
