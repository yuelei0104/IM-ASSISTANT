package com.yuelei.imchat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL stores only vector data. The caller supplies an authorized MySQL snapshot. */
@Component
public class GroupVectorStore {
    private static final Logger log = LoggerFactory.getLogger(GroupVectorStore.class);
    private static final int SEARCH_BATCH_SIZE = 256;
    private final String url;
    private final String user;
    private final String password;
    private final int annMinIds;
    private final int annEfSearch;

    public GroupVectorStore(@Value("${im.agent.pgvector.url}") String url,
                            @Value("${im.agent.pgvector.user}") String user,
                            @Value("${im.agent.pgvector.password:}") String password,
                            @Value("${im.agent.pgvector.ann-min-ids:1200}") int annMinIds,
                            @Value("${im.agent.pgvector.ann-ef-search:200}") int annEfSearch) {
        // Only the local development database or the private Compose service.
        if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|pgvector):[0-9]{1,5}/im_vectors(?:\\?.*)?"))
            throw new IllegalArgumentException("群聊向量库必须连接本机或内部 pgvector 服务");
        if (annMinIds < 0) throw new IllegalArgumentException("近似检索阈值不能为负");
        if (annEfSearch < 1 || annEfSearch > 4000) throw new IllegalArgumentException("hnsw.ef_search 超出范围");
        this.url = url;
        this.user = user;
        this.password = password;
        this.annMinIds = annMinIds;
        this.annEfSearch = annEfSearch;
    }

    /** One message may own several chunk vectors; the hash covers the whole message. */
    public record EmbeddedMessage(long messageId, String hash, List<float[]> chunks) { }
    public record StoredIndex(String hash, int chunks) { }
    public record Hit(long messageId, double distance) { }

    public boolean configured() { return !password.isBlank(); }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** What the index currently holds per message: the text hash and how many chunks. */
    public Map<Long, StoredIndex> stored(long groupId, String model, List<Long> eligibleIds) {
        if (eligibleIds.isEmpty()) return Map.of();
        String sql = "SELECT message_id, min(content_hash) AS content_hash, count(*) AS chunks "
                + "FROM im_group_message_embedding "
                + "WHERE group_id = ? AND model = ? AND message_id = ANY (?) "
                + "GROUP BY message_id";
        try (var connection = connect(); var query = connection.prepareStatement(sql)) {
            query.setLong(1, groupId);
            query.setString(2, model);
            query.setArray(3, connection.createArrayOf("bigint", eligibleIds.toArray(Long[]::new)));
            var result = new HashMap<Long, StoredIndex>();
            try (var rows = query.executeQuery()) {
                while (rows.next())
                    result.put(rows.getLong(1), new StoredIndex(rows.getString(2), rows.getInt(3)));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("群聊向量库读取失败", e);
        }
    }

    public void upsert(long groupId, String model, List<EmbeddedMessage> messages) {
        if (messages.isEmpty()) return;
        String clear = "DELETE FROM im_group_message_embedding "
                + "WHERE group_id = ? AND model = ? AND message_id = ANY (?)";
        String insert = "INSERT INTO im_group_message_embedding "
                + "(group_id, message_id, model, chunk_no, content_hash, embedding) "
                + "VALUES (?, ?, ?, ?, ?, CAST(? AS vector))";
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                // Replacing every chunk keeps stale windows from surviving when a
                // message shrinks, and makes the chunk count authoritative.
                try (var statement = connection.prepareStatement(clear)) {
                    statement.setLong(1, groupId);
                    statement.setString(2, model);
                    statement.setArray(3, connection.createArrayOf("bigint",
                            messages.stream().map(EmbeddedMessage::messageId).toArray(Long[]::new)));
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement(insert)) {
                    for (var message : messages) {
                        List<float[]> chunks = message.chunks();
                        for (int index = 0; index < chunks.size(); index++) {
                            statement.setLong(1, groupId);
                            statement.setLong(2, message.messageId());
                            statement.setString(3, model);
                            statement.setInt(4, index);
                            statement.setString(5, message.hash());
                            statement.setString(6, vector(chunks.get(index)));
                            statement.addBatch();
                        }
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("群聊向量库写入失败", e);
        }
    }

    public List<Hit> nearest(long groupId, String model, List<Long> eligibleIds,
                             float[] question, int limit) {
        if (eligibleIds.isEmpty() || limit < 1) return List.of();
        if (annMinIds > 0 && eligibleIds.size() > annMinIds) {
            var approximate = nearestApproximate(groupId, model, eligibleIds, question, limit);
            if (approximate.size() >= limit) return approximate;
            // A filtered HNSW scan can stall below the requested size. Exact
            // scoring is slower but never silently returns a short list.
            log.info("ANN recall returned {} of {}, falling back to exact scan",
                    approximate.size(), limit);
        }
        return nearestExact(groupId, model, eligibleIds, question, limit);
    }

    private List<Hit> nearestExact(long groupId, String model, List<Long> eligibleIds,
                                   float[] question, int limit) {
        // Rank each authorized ID batch exactly, then merge their best entries.
        // The union of per-batch top results contains the global top results, so
        // JDBC array and SQL work stay bounded without limiting which history is searched.
        String sql = "WITH permitted AS MATERIALIZED ("
                + "SELECT message_id, embedding FROM im_group_message_embedding "
                + "WHERE group_id = ? AND model = ? AND message_id = ANY (?)"
                + ") SELECT message_id, min(embedding <=> CAST(? AS vector)) AS score "
                + "FROM permitted GROUP BY message_id ORDER BY score, message_id LIMIT ?";
        String questionVector = vector(question);
        var best = new LinkedHashMap<Long, Double>();
        try (var connection = connect(); var query = connection.prepareStatement(sql)) {
            for (int offset = 0; offset < eligibleIds.size(); offset += SEARCH_BATCH_SIZE) {
                var batch = eligibleIds.subList(offset,
                        Math.min(offset + SEARCH_BATCH_SIZE, eligibleIds.size()));
                query.setLong(1, groupId);
                query.setString(2, model);
                query.setArray(3, connection.createArrayOf("bigint", batch.toArray(Long[]::new)));
                query.setString(4, questionVector);
                query.setInt(5, limit);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        long id = rows.getLong(1);
                        double distance = rows.getDouble(2);
                        best.merge(id, distance, Math::min);
                    }
                }
            }
            return best.entrySet().stream()
                    .map(entry -> new Hit(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparingDouble(Hit::distance)
                            .thenComparing(Comparator.comparingLong(Hit::messageId).reversed()))
                    .limit(limit)
                    .toList();
        } catch (SQLException e) {
            throw new IllegalStateException("群聊向量检索失败", e);
        }
    }

    /**
     * HNSW-backed scan used once a group's history grows large. The permission
     * filter stays in SQL; iterative scan keeps pulling candidates until the
     * filter yields enough rows instead of returning a silent short list.
     */
    private List<Hit> nearestApproximate(long groupId, String model, List<Long> eligibleIds,
                                         float[] question, int limit) {
        String sql = "SELECT message_id, embedding <=> CAST(? AS vector) AS score "
                + "FROM im_group_message_embedding "
                + "WHERE group_id = ? AND model = ? AND message_id = ANY (?) "
                + "ORDER BY score LIMIT ?";
        String questionVector = vector(question);
        int rowsWanted = Math.min(limit * 4, 200);
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var settings = connection.createStatement()) {
                    settings.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
                    settings.execute("SET LOCAL hnsw.ef_search = " + annEfSearch);
                }
                var hits = new ArrayList<Hit>();
                try (var query = connection.prepareStatement(sql)) {
                    query.setString(1, questionVector);
                    query.setLong(2, groupId);
                    query.setString(3, model);
                    query.setArray(4, connection.createArrayOf("bigint", eligibleIds.toArray(Long[]::new)));
                    query.setInt(5, rowsWanted);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) hits.add(new Hit(rows.getLong(1), rows.getDouble(2)));
                    }
                }
                connection.commit();
                // Several chunks of one message collapse to its best chunk.
                var collapsed = new LinkedHashMap<Long, Double>();
                for (var hit : hits) collapsed.merge(hit.messageId(), hit.distance(), Math::min);
                return collapsed.entrySet().stream()
                        .map(entry -> new Hit(entry.getKey(), entry.getValue()))
                        .sorted(Comparator.comparingDouble(Hit::distance))
                        .limit(limit)
                        .toList();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            // A missing index or an older pgvector build must degrade, not fail.
            log.warn("ANN recall unavailable, using exact scan: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    public void removeMessage(long groupId, long messageId) {
        delete("DELETE FROM im_group_message_embedding WHERE group_id = ? AND message_id = ?", groupId, messageId);
    }

    public void removeGroup(long groupId) {
        delete("DELETE FROM im_group_message_embedding WHERE group_id = ?", groupId, null);
    }

    private void delete(String sql, long groupId, Long messageId) {
        if (!configured()) return;
        try (var connection = connect(); var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, groupId);
            if (messageId != null) statement.setLong(2, messageId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("群聊向量清理失败", e);
        }
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String vector(float[] values) {
        if (values.length != 1024) throw new IllegalArgumentException("向量维度必须为 1024");
        var result = new StringBuilder(8192).append('[');
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("向量包含无效数值");
            if (i > 0) result.append(',');
            result.append(values[i]);
        }
        return result.append(']').toString();
    }
}
