package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KotlinSourceLightParserTest {

    @Test
    void documentTypes_findsAllMongoDocumentsInMultiClassFile() {
        String content = """
                package com.quotient.platform.nre.libs.mongo.model

                import org.springframework.data.mongodb.core.mapping.Document

                @Document(collection = "user_recommendations")
                data class UserRecommendations(val id: String)

                @Document(collection = "offer_info")
                data class OfferInfo(val partnerId: String)
                """;

        List<KotlinSourceLightParser.KotlinDocumentType> docs =
                KotlinSourceLightParser.documentTypes("MongoModels.kt", content);

        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).classFqn()).isEqualTo(
                "com.quotient.platform.nre.libs.mongo.model.UserRecommendations");
        assertThat(docs.get(0).collection()).isEqualTo("user_recommendations");
        assertThat(docs.get(1).collection()).isEqualTo("offer_info");
    }

    @Test
    void firstTopLevelTypeFqn_resolvesDataClass() {
        String content = """
                package com.example
                data class FooBar(val x: Int)
                """;

        assertThat(KotlinSourceLightParser.firstTopLevelTypeFqn("Foo.kt", content))
                .contains("com.example.FooBar");
    }
}
