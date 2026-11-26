package com.example.recommend.chat.config;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

class QualityConversationV2PlanTest {
    @Test void threeDollarApprovalDoesNotRelaxPerRunOrLowerPreparedCaps() {
        QualityConversationV2Plan.caps(.9833961,.60,.60);
        QualityConversationV2Plan.caps(.9833961,.01,.01);
        assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.caps(.9833961,.011,.01));
        assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.caps(2.99,.02,.60));
        for(double cap:new double[]{0,-1,.601,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.caps(.9833961,cap,.60));
        double worst=QualityPilotBudget.reservedEstimate(60,600_000,122_880);
        assertEquals(.399744,worst,1e-9);assertTrue(worst<.60);
        var budget=new QualityPilotBudget(.60);
        for(int i=0;i<60;i++)budget.reserve(10_000,2048);
        assertThrows(QualityPilotBudget.LimitReached.class,()->budget.reserve(1,16));
        assertEquals(60,budget.requests);
    }
    @Test void v2UsesNewOutputWithoutChangingFrozenScopeOrV1Guards(@TempDir Path temp) throws Exception {
        String old=System.getProperty("qualityConversationV2");
        try {
            System.setProperty("qualityConversationV2","true");
            assertEquals(QualityConversationV2Plan.OUT,QualityConversationTest.output());
            assertEquals(QualityConversationV2Plan.PROTOCOL,QualityConversationTest.protocol());
            assertEquals(QualityConversationPlan.CASE_IDS,QualityConversationV2Plan.CASE_IDS);
            System.setProperty("qualityConversationV2","false");
            assertEquals(QualityConversationPlan.OUT,QualityConversationTest.output());
            assertEquals(QualityConversationPlan.PROTOCOL,QualityConversationTest.protocol());
            Files.writeString(temp.resolve("launch-started.json"),"{}");QualityConversationV2Plan.notRun(temp);
            Files.writeString(temp.resolve("pilot-123.json"),"{\"preserved\":true}");
            assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.notRun(temp));
            assertEquals("{\"preserved\":true}",Files.readString(temp.resolve("pilot-123.json")));
        } finally {if(old==null)System.clearProperty("qualityConversationV2");else System.setProperty("qualityConversationV2",old);}
    }
    @Test void ninthReportIsThePreservedPartialConversation() {
        assertEquals(9,QualityConversationV2Plan.priorPins().size());
        assertEquals("c173a2aa9188f6e3617056034558cf7bc6299b85063dd46e1824b6f55f06b7b2",
                QualityConversationV2Plan.priorPins().get("target/quality-conversation-20261125/pilot-20261125141919368863300.json"));
    }
    @Test @EnabledIfSystemProperty(named="qualityConversationV2Prepared",matches="true")
    @SuppressWarnings("unchecked")
    void preparedLedgerAndLowerCapCannotBeRaised(@TempDir Path temp) throws Exception {
        Path original=QualityConversationV2Plan.OUT.resolve("run-plan.json");
        var plan=JSON.readTree(Files.readAllBytes(original));double cap=plan.get("runMaxEstimatedUsd").asDouble();
        assertEquals(.9833961,QualityConversationV2Plan.validate(Path.of(""),original,cap),1e-9);
        var copy=(Map<String,Object>)JSON.readValue(Files.readAllBytes(original),Map.class);
        double small=Math.min(.05,cap);copy.put("runMaxEstimatedUsd",small);copy.put("cumulativeMaxEstimatedUsd",.9833961+small);
        Path f=temp.resolve("plan.json");Files.write(f,JSON.writeValueAsBytes(copy));
        assertEquals(.9833961,QualityConversationV2Plan.validate(Path.of(""),f,small),1e-9);
        assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.validate(Path.of(""),f,small+.001));
        copy.put("approvedCumulativeUsd",1);Files.write(f,JSON.writeValueAsBytes(copy));
        assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.validate(Path.of(""),f,small));
        copy.put("approvedCumulativeUsd",3);copy.put("priorReservationEstimateUsd",0);Files.write(f,JSON.writeValueAsBytes(copy));
        assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.validate(Path.of(""),f,small));
        copy.put("priorReservationEstimateUsd",.9833961);copy.put("protocol","OTHER");Files.write(f,JSON.writeValueAsBytes(copy));
        assertThrows(IllegalStateException.class,()->QualityConversationV2Plan.validate(Path.of(""),f,small));
    }
}
