package com.example.recommend.chat.service;

import com.example.recommend.chat.domain.ChatMessage;
import com.example.recommend.chat.domain.ChatRoom;
import com.example.recommend.domain.User;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.mapping.Column;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSchemaTest {
    @Test void referenceMetadataIsNotPartOfTheHttpResponseContract() {
        var response = new com.example.recommend.chat.dto.ChatResponse("작품",
                new com.example.recommend.chat.dto.ChatResponse.Usage(0, 0, 0), true, java.util.List.of(42L));
        var json = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(response);
        assertEquals(3, json.size());
        assertFalse(json.has("contentIds"));
        assertTrue(json.has("message"));
    }
    @Test void mysqlMessageColumnCanStoreTheSupportedOutputBudget() {
        // Generate dialect metadata offline: no application config, credentials or DB connection.
        try (var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .build()) {
            var metadata = (MetadataImplementor) new MetadataSources(registry)
                    .addAnnotatedClass(User.class).addAnnotatedClass(ChatRoom.class)
                    .addAnnotatedClass(ChatMessage.class).buildMetadata();
            var table = metadata.getEntityBinding(ChatMessage.class.getName()).getTable();
            String sqlType = table.getColumn(new Column("text")).getSqlType(metadata);
            assertTrue(sqlType.equals("longtext") || sqlType.equals("mediumtext"),
                    "Maximum supported answer exceeds small MySQL text columns; generated type: " + sqlType);
        }
    }

    @Test void mysqlSchemaUpdateWidensAnExistingTinytextColumn() {
        try (var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect").build()) {
            var metadata = (MetadataImplementor) new MetadataSources(registry)
                    .addAnnotatedClass(User.class).addAnnotatedClass(ChatRoom.class)
                    .addAnnotatedClass(ChatMessage.class).buildMetadata();
            var table = metadata.getEntityBinding(ChatMessage.class.getName()).getTable();
            var existing = mock(org.hibernate.tool.schema.extract.spi.TableInformation.class);
            for (var column : table.getColumns()) {
                if (column.getName().equals("content_references")) continue; // Existing pre-6.3 table.
                var info = mock(org.hibernate.tool.schema.extract.spi.ColumnInformation.class);
                boolean body = column.getName().equals("text");
                when(info.getTypeName()).thenReturn(body ? "tinytext" : column.getSqlType(metadata));
                when(info.getTypeCode()).thenReturn(body ? java.sql.Types.LONGVARCHAR : column.getSqlTypeCode(metadata));
                var length = column.getColumnSize(metadata.getDatabase().getDialect(), metadata).getLength();
                when(info.getColumnSize()).thenReturn(body || length == null ? 255 : length.intValue());
                when(existing.getColumn(org.hibernate.boot.model.naming.Identifier.toIdentifier(column.getName())))
                        .thenReturn(info);
            }
            var database = metadata.getDatabase();
            var context = org.hibernate.boot.model.relational.internal.SqlStringGenerationContextImpl
                    .fromConfigurationMapForMigration(database.getJdbcEnvironment(), database, java.util.Map.of());
            var sql = database.getDialect().getTableMigrator().getSqlAlterStrings(table, metadata, existing, context);
            assertTrue(java.util.Arrays.stream(sql).anyMatch(statement ->
                    statement.contains("modify column text longtext not null")), java.util.Arrays.toString(sql));
            assertTrue(java.util.Arrays.stream(sql).anyMatch(statement ->
                    statement.contains("add column content_references varchar(128)")), java.util.Arrays.toString(sql));
        }
    }
}
