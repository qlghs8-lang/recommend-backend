package com.example.recommend.chat.config;

import com.example.recommend.chat.client.*;
import com.example.recommend.chat.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL/Hibernate reader and search; public application reads, owned scratch writes only. */
@EnabledIfSystemProperty(named="chatReviewedMetadataMysql",matches="true")
class ReviewedMetadataMysqlTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    static void event(JdbcTemplate jdbc,String payload,int enabled) throws Exception {
        var root=JSON.readTree(payload);
        String previous=jdbc.query("SELECT event_sha FROM catalog_metadata_search_events ORDER BY event_id DESC LIMIT 1",(rs,i)->rs.getString(1)).stream().findFirst().orElse(null);
        var body=new TreeMap<String,Object>();body.put("policy",root.get("policy").asString());body.put("previous_sha",previous);body.put("enabled",enabled);
        body.put("release_sha",root.get("release_sha").asString());body.put("search_sha",hash(payload));
        body.put("reason","Owned MySQL Java integration test");body.put("actor","CODEX_DECLARED_NOT_AUTHENTICATED");body.put("declared_at","2026-11-26T14:20:00+00:00");
        String text=JSON.writeValueAsString(body);
        jdbc.update("INSERT INTO catalog_metadata_search_events(event_sha,previous_sha,enabled,release_sha,search_sha,payload,event_body,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                hash(text),previous,enabled,body.get("release_sha"),body.get("search_sha"),payload,text);
    }
    @Test void actualPublicCatalogUsesReviewedDiscoveryAndWithdrawalWithMysqlDialect() throws Exception {
        var checks=new LinkedHashMap<String,Object>();
        try(var db=new RuntimeRecommendationAcceptanceTest.Database()) {
            var before=db.readPublic();db.allocate();
            var dataSource=new DriverManagerDataSource(db.scratchUrl(),db.username,db.password);
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);factory.setPackagesToScan("com.example.recommend.domain","com.example.recommend.chat.domain");
            var adapter=new HibernateJpaVendorAdapter();adapter.setDatabasePlatform("org.hibernate.dialect.MySQLDialect");factory.setJpaVendorAdapter(adapter);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create","hibernate.show_sql","false",
                    "hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));factory.afterPropertiesSet();
            try {
                RuntimeRecommendationAcceptanceTest.copyPublic(dataSource,before);
                var metadata=new ReviewedMetadataSearch(dataSource);
                var beans=new StaticListableBeanFactory(Map.of("reviewedMetadataSearch",metadata));
                try(var em=Objects.requireNonNull(factory.getObject()).createEntityManager()) {
                    var retriever=new CatalogRetriever(em,beans.getBeanProvider(ReviewedMetadataSearch.class));
                    var fish=List.of(new AiMessage(AiMessage.Role.USER,"fish 추천"));
                    assertTrue(retriever.retrieve(fish).stream().noneMatch(w->w.id()==45));
                    var jdbc=new JdbcTemplate(dataSource);jdbc.execute(Files.readString(Path.of("docs/sql/catalog_metadata_search_v1.sql")));
                    String payload=Files.readString(Path.of("src/main/resources/catalog/metadata-search-v1.json"));
                    event(jdbc,payload,1);
                    assertTrue(retriever.retrieve(fish).stream().anyMatch(w->w.id()==45));
                    assertTrue(retriever.retrieve(List.of(new AiMessage(AiMessage.Role.USER,"Finding Nemo 추천"))).stream().anyMatch(w->w.id()==45));
                    var tv=new RecommendationCriteria(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.MEDIA,"TV","TV",List.of())),Set.of(),Set.of(),false);
                    assertTrue(retriever.retrieve(fish,tv).stream().noneMatch(w->w.id()==45));
                    var excluded=new RecommendationCriteria(List.of(new RecommendationConstraint(RecommendationConstraint.Kind.TITLE_EXCLUDE,"Finding Nemo","Finding Nemo",List.of())),Set.of(),Set.of(),false);
                    assertTrue(retriever.withReviewedAliasExclusions(excluded).excludedIds().contains(45L));
                    assertTrue(retriever.retrieve(fish,excluded).stream().noneMatch(w->w.id()==45));
                    var work=before.works().stream().filter(w->w.id()==45).findFirst().orElseThrow();
                    jdbc.update("UPDATE contents SET overview=? WHERE id=45",work.overview()+" ");em.clear();
                    assertTrue(retriever.retrieve(fish).stream().noneMatch(w->w.id()==45));
                    jdbc.update("UPDATE contents SET overview=? WHERE id=45",work.overview());em.clear();
                    event(jdbc,payload,0);assertTrue(retriever.retrieve(fish).stream().noneMatch(w->w.id()==45));
                    assertEquals(work.title(),retriever.detail(45).orElseThrow().title());
                    checks.put("mysqlKeywordAndAliasDiscovery",true);checks.put("withdrawalAndExactStaleFingerprint",true);
                    checks.put("hardMediaFilterPreserved",true);checks.put("catalogRows",468);checks.put("knownDurations",21);
                }
            } finally {factory.destroy();}
            assertEquals(before,db.readPublic());checks.put("applicationPublicCatalogUnchanged",true);
        }
        checks.put("ownedScratchRemovedAndAbsenceVerified",true);checks.put("providerCalls",0);checks.put("status","PASS");
        Path folder=Path.of("target/metadata-search-20261126");Files.createDirectories(folder);
        Files.writeString(folder.resolve("java-mysql-verification.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(checks));
    }
}
