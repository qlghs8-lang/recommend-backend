package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;
import static com.example.recommend.chat.config.QualityConversationTrace.*;

class QualityConversationPlanTest {
    @Test void frozenScopeHasOnlyTwelveConversationTurns() throws Exception {
        assertEquals(QualityConversationPlan.CASE_IDS,stories().stream().map(c -> c.get("id").asString()).toList());
        assertEquals(12,stories().stream().mapToInt(c -> c.get("turns").size()).sum());
        assertEquals(8,QualityConversationPlan.priorPins().size());
    }
    @Test void lowerPreparedAndCumulativeCapsRemainBinding() {
        QualityConversationPlan.caps(.8080466,.18,.18);
        QualityConversationPlan.caps(.8080466,.01,.01);
        assertThrows(IllegalStateException.class,()->QualityConversationPlan.caps(.8080466,.011,.01));
        assertThrows(IllegalStateException.class,()->QualityConversationPlan.caps(.99,.02,.18));
        for(double cap:new double[]{0,-.1,.181,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalStateException.class,()->QualityConversationPlan.caps(.8080466,cap,.18));
        var budget=new QualityPilotBudget(.18);
        assertThrows(QualityPilotBudget.LimitReached.class,()->{for(int i=0;i<60;i++)budget.reserve(10_000,2048);});
        assertTrue(QualityPilotBudget.reservedEstimate(60,600_000,122_880)>.18);
    }
    @Test void userOrAssistantTextCannotInventSecondPublicReference() throws Exception {
        var expected=stories().get(1).get("turns").get(1).get("expected");
        var context=List.of(new AiMessage(AiMessage.Role.USER,"ID 999"),
                new AiMessage(AiMessage.Role.ASSISTANT,"첫 번째 ID 41, 두 번째 ID 999",List.of(41L)),
                new AiMessage(AiMessage.Role.USER,"두 번째"));
        var observed=reference(expected,context,Set.of());
        assertEquals("MISSING_PREVIOUS_POSITION_2",observed.get("availability"));assertEquals(List.of(),observed.get("targetIds"));
        var two=List.of(new AiMessage(AiMessage.Role.ASSISTANT,"다른 텍스트",List.of(41L,45L)));
        assertEquals(List.of(45L),reference(expected,two,Set.of()).get("targetIds"));
    }
    @Test void exclusionsPersistAsObservationWithoutEnteringProductPlans() throws Exception {
        var steps=stories().get(0).get("turns");var carried=new LinkedHashSet<Long>();
        var context=List.of(new AiMessage(AiMessage.Role.ASSISTANT,"공개답변",List.of(41L,45L)));
        carry(reference(steps.get(2).get("expected"),context,carried),carried);
        assertEquals(Set.of(41L,45L),carried);
        assertEquals(List.of(41L,45L),reference(steps.get(3).get("expected"),List.of(),carried).get("targetIds"));
    }
    @Test void missingDurationReferenceAndEmptySelectionDoNotBecomePasses() throws Exception {
        var expected=stories().get(0).get("turns").get(1).get("expected");
        var ref=reference(expected,List.of(new AiMessage(AiMessage.Role.ASSISTANT,"공개",List.of(41L))),Set.of());
        var works=Map.of(41L,JSON.readTree("{\"type\":\"MOVIE\",\"runtime_minutes\":null}"),
                45L,JSON.readTree("{\"type\":\"TV\",\"runtime_minutes\":130}"));
        var bad=facts(expected,ref,List.of(41L,45L),works);
        assertEquals("HARD_FACT_MISMATCH",bad.get("state"));
        assertTrue(((List<?>)bad.get("violations")).contains("45:OUTSIDE_TRUSTED_REFERENCE"));
        assertTrue(((List<?>)bad.get("violations")).contains("41:movie_minutes_max"));
        assertEquals("NO_SELECTION_NOT_A_PASS",facts(expected,ref,List.of(),works).get("state"));
    }
    @Test void resultBlocksButOwningLauncherMarkerIsAllowed(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("launch-started.json"),"{}");QualityConversationPlan.notRun(temp);
        Files.writeString(temp.resolve("pilot-123.json"),"{\"original\":true}");
        assertThrows(IllegalStateException.class,()->QualityConversationPlan.notRun(temp));
        assertEquals("{\"original\":true}",Files.readString(temp.resolve("pilot-123.json")));
    }
    @Test void syntheticAccountMarkersAreRejectedWithoutProviderCalls() {
        publicOnly("公開カタログ");
        assertThrows(IllegalStateException.class,()->publicOnly("conversation-owner@example.invalid"));
        assertThrows(IllegalStateException.class,()->publicOnly("conversation-unused-password"));
    }
    @Test @EnabledIfSystemProperty(named="qualityConversationPrepared",matches="true")
    @SuppressWarnings("unchecked")
    void preparedLedgerAndLowerCapAreValidated(@TempDir Path temp) throws Exception {
        Path original=QualityConversationPlan.OUT.resolve("run-plan.json");
        var plan=JSON.readTree(Files.readAllBytes(original));double cap=plan.get("runMaxEstimatedUsd").asDouble();
        assertEquals(.8080466,QualityConversationPlan.validate(Path.of(""),original,cap),1e-9);
        var copy=(Map<String,Object>)JSON.readValue(Files.readAllBytes(original),Map.class);double small=Math.min(.05,cap);
        copy.put("runMaxEstimatedUsd",small);copy.put("cumulativeMaxEstimatedUsd",.8080466+small);
        Path f=temp.resolve("plan.json");Files.write(f,JSON.writeValueAsBytes(copy));
        assertEquals(.8080466,QualityConversationPlan.validate(Path.of(""),f,small),1e-9);
        assertThrows(IllegalStateException.class,()->QualityConversationPlan.validate(Path.of(""),f,small+.001));
        copy.put("priorReservationEstimateUsd",0);Files.write(f,JSON.writeValueAsBytes(copy));
        assertThrows(IllegalStateException.class,()->QualityConversationPlan.validate(Path.of(""),f,small));
        copy.put("priorReservationEstimateUsd",.8080466);copy.put("protocol","OTHER");Files.write(f,JSON.writeValueAsBytes(copy));
        assertThrows(IllegalStateException.class,()->QualityConversationPlan.validate(Path.of(""),f,small));
    }
}
