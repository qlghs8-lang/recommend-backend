package com.example.recommend.chat.config;

import com.example.recommend.chat.repository.CatalogSnapshotRepository;
import com.example.recommend.chat.service.CatalogRetriever;
import com.example.recommend.repository.ContentRepository;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.recommend.chat.config.QualityBaselineTest.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
@Import(CatalogRetriever.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityRemainingFixtureTest {
    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired CatalogRetriever retriever;
    @Autowired CatalogSnapshotRepository snapshots;
    @Autowired ContentRepository contents;

    @Test void syntheticOwnerIsolationAndKnownViewGapAreRecordedWithoutAnyProviderCall() throws Exception {
        var catalog = new QualityBaselineTest(); catalog.dataSource=dataSource; catalog.em=em; catalog.contents=contents; catalog.retriever=retriever;
        var synthetic = new QualityRemainingFixture(dataSource);
        try {
            catalog.install(JSON.readTree(Files.readAllBytes(OUT.resolve("public-snapshot.json"))));
            synthetic.install(); var audit = synthetic.audit(em,retriever,snapshots);
            assertEquals(false,audit.get("viewOnlyExcluded"),"Record the current VIEW exclusion gap; do not silently fix the product in baseline tooling");
            assertEquals(true,audit.get("ownLikeAndDislikeExcluded"));
            assertEquals(true,audit.get("foreignReactionsDoNotExcludeOwnCandidates"));
            assertEquals(0,audit.get("providerCalls"));
            Files.createDirectories(QualityRemainingPlan.OUT);
            Files.writeString(QualityRemainingPlan.OUT.resolve("synthetic-audit.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
            assertEquals("FIXED_SYNTHETIC_CONDITION_MISMATCH",synthetic.policyReview(java.util.List.of(100L)).get("evaluation"));
        } finally {
            assertTrue(synthetic.cleanup()); catalog.cleanup();
        }
        try (var c=dataSource.getConnection();var q=c.prepareStatement("select count(*) from users where id in (?,?)")) {
            q.setLong(1,QualityRemainingFixture.OWNER);q.setLong(2,QualityRemainingFixture.OTHER);
            try(var rows=q.executeQuery()){rows.next();assertEquals(0,rows.getInt(1));}
        }
    }
    @Test void syntheticAccountMarkersAreNeverAllowedIntoProviderParameters() {
        QualityRemainingFixture.assertNoSyntheticAccountData("fixed public catalog text");
        for(String marker:java.util.List.of("remaining-owner@example.invalid","remaining-other@example.invalid","synthetic-unused-password"))
            assertThrows(IllegalStateException.class,()->QualityRemainingFixture.assertNoSyntheticAccountData(marker));
    }
}
