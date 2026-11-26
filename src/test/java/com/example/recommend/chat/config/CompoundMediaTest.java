package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompoundMediaTest {
    static List<CompoundMediaLiveTest.Scenario> scenarios() { return CompoundMediaLiveTest.scenarios(); }
    @ParameterizedTest(name = "{0}") @MethodSource("scenarios")
    void fixedMediaScopeOraclesPassTheProviderDecoderAndServerContracts(CompoundMediaLiveTest.Scenario scenario) {
        var test = scenario.test(); var operations = new ArrayList<Map<String, Object>>();
        for (var route : test.routes()) {
            var operation = new LinkedHashMap<String, Object>(); operation.put("route", route);
            if (route.startsWith("LIST_")) operation.put("media", scenario.listMedia().name());
            else {
                String media = test.constraints().stream().filter(c -> c.kind().equals("MEDIA"))
                        .map(ClassifierStabilityCorpus.Expected::value).findFirst().orElse("ANY");
                operation.put("media", media);
                operation.put("mediaSource", media.equals("ANY") ? null : media.equals("MOVIE") ? "영화" : "시리즈");
            }
            operations.add(operation);
        }
        var constraints = test.constraints().stream().filter(c -> !c.kind().equals("MEDIA"))
                .map(c -> new RecommendationConstraint(RecommendationConstraint.Kind.valueOf(c.kind()), c.value(), "최신 공개순", List.of())).toList();
        String wire = ConversationQualityCorpus.JSON.writeValueAsString(Map.of("requests", operations, "comparison", false,
                "splitAfter", scenario.splitAfter(), "constraints", constraints));
        var plan = ClassifierPlan.parse(wire, test.context());
        assertTrue(ClassifierStabilityCorpus.issues(test, plan).isEmpty());
        assertTrue(CompoundMediaLiveTest.listScopeMatches(scenario, plan));
    }
}
