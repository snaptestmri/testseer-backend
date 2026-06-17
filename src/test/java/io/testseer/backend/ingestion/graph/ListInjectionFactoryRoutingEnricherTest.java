package io.testseer.backend.ingestion.graph;

import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListInjectionFactoryRoutingEnricherTest {

    @Test
    void enrichByFactory_detectsListInjectionAdapterMap() {
        String factorySource = """
                public class PartnerAdapterFactory {
                    public PartnerAdapterFactory(List<BaseAdapter> adapters) {
                        this.adapterEventProcessorMap = adapters.stream()
                            .collect(Collectors.toMap(BaseAdapter::getAdapterName,
                                Function.identity(), (a, b) -> b));
                    }
                    public BaseAdapter getProcessor(String adapterName) {
                        return adapterEventProcessorMap.get(adapterName);
                    }
                }
                """;

        String hyveeSource = """
                public class HyveeOfferAdapter extends OfferBaseAdapter {
                    public String getAdapterName() { return this.getClass().getSimpleName(); }
                }
                """;

        String dgSource = """
                public class DGAdapter implements BaseAdapter {
                    public String getAdapterName() { return DgConstants.ADAPTER_NAME; }
                }
                """;
        String dgConstants = "ADAPTER_NAME = \"DGAdapter\";";

        ParsedModel factory = model(
                "com.example.PartnerAdapterFactory",
                factorySource,
                List.of("List<BaseAdapter>"),
                List.of());

        ParsedModel hyvee = model(
                "com.example.HyveeOfferAdapter",
                hyveeSource,
                List.of(),
                List.of());

        ParsedModel dg = model(
                "com.example.DGAdapter",
                dgSource + dgConstants,
                List.of(),
                List.of("com.example.BaseAdapter"));

        Map<String, String> sources = Map.of(
                factory.classFqn(), factorySource,
                hyvee.classFqn(), hyveeSource,
                dg.classFqn(), dgSource + dgConstants);

        var routes = ListInjectionFactoryRoutingEnricher.enrichByFactory(
                List.of(factory, hyvee, dg), sources);

        assertThat(routes).containsKey("com.example.PartnerAdapterFactory");
        assertThat(routes.get("com.example.PartnerAdapterFactory"))
                .extracting(ParsedModel.FactoryRoutingDef::routingKey)
                .contains("HyveeOfferAdapter", "DGAdapter");
    }

    @Test
    void inferRoutingKey_usesConstantAdapterName() {
        String source = "return DgConstants.ADAPTER_NAME; ADAPTER_NAME = \"DGAdapter\";";
        assertThat(ListInjectionFactoryRoutingEnricher.inferRoutingKey(
                "com.example.DGAdapter", source)).isEqualTo("DGAdapter");
    }

    private static ParsedModel model(
            String fqn,
            String source,
            List<String> constructorParams,
            List<String> interfaces) {
        return new ParsedModel(
                fqn + ".java", fqn, List.of(), constructorParams, List.of(),
                List.of(), List.of(), false, null, null,
                List.of(new ParsedModel.MethodDef("getProcessor", null, "BaseAdapter", List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), null, interfaces);
    }
}
