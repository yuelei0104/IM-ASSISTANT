package com.yuelei.imchat.service;

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
import java.util.List;
import java.util.Map;

/** PostgreSQL stores only vector data. The caller supplies an authorized MySQL snapshot. */
@Component
public class GroupVectorStore {
    private static final int SEARCH_BATCH_SIZE = 256;
    private final String url;
    private final String user;
    private final String password;

    public GroupVectorStore(@Value("${im.agent.pgvector.url}") String url,
                            @Value("${im.agent.pgvector.user}") String user,
                            @Value("${im.agent.pgvector.password:}") String password) {
        // Only the local development database or the private Compose service.
        if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|pgvector):[0-9]{1,5}/im_vectors(?:\\?.*)?"))
            throw new IllegalArgumentException("群聊向量库必须连接本机或内部 pgvector 服务");
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public boolean configured() { return !password.isBlank(); }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public Map<Long, String> hashes(long groupId, String model, List<Long> eligibleIds) {
        if (eligibleIds.isEmpty()) return Map.of();
        String sql = "SELECT message_id, content_hash FROM im_group_message_embedding "
                + "WHERE group_id = ? AND model = ? AND message_id = ANY (?)";
        try (var connection = connect(); var query = connection.prepareStatement(sql)) {
            query.setLong(1, groupId);
            query.setString(2, model);
            query.setArray(3, connection.createArrayOf("bigint", eligibleIds.toArray(Long[]::new)));
            var result = new HashMap<Long, String>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.put(rows.getLong(1), rows.getString(2));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("群聊向量库读取失败", e);
        }
    }

    public void upsert(long groupId, String model, List<GroupAgentEngine.Candidate> messages,
                       List<float[]> vectors) {
        if (messages.size() != vectors.size()) throw new IllegalArgumentException("向量数量不匹配");
        String sql = "INSERT INTO im_group_message_embedding "
                + "(group_id, message_id, model, content_hash, embedding) "
                + "VALUES (?, ?, ?, ?, CAST(? AS vector)) "
                + "ON CONFLICT (group_id, message_id, model) DO UPDATE SET "
                + "content_hash = EXCLUDED.content_hash, embedding = EXCLUDED.embedding";
        try (var connection = connect(); var insert = connection.prepareStatement(sql)) {
            for (int i = 0; i < messages.size(); i++) {
                var message = messages.get(i);
                insert.setLong(1, groupId);
                insert.setLong(2, message.id());
                insert.setString(3, model);
                insert.setString(4, hash(message.text()));
                insert.setString(5, vector(vectors.get(i)));
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("群聊向量库写入失败", e);
        }
    }

    public List<Long> nearest(long groupId, String model, List<Long> eligibleIds, float[] question) {
        if (eligibleIds.isEmpty()) return List.of();
        // Rank each authorized ID batch exactly, then merge their best three.
        // The union of per-batch top threes contains the global top three.
        // This keeps JDBC array and SQL work bounded without limiting history.
        String sql = "WITH permitted AS MATERIALIZED ("
                + "SELECT message_id, embedding FROM im_group_message_embedding "
                + "WHERE group_id = ? AND model = ? AND message_id = ANY (?)"
                + ") SELECT message_id, embedding <=> CAST(? AS vector) AS score FROM permitted "
                + "ORDER BY score, message_id LIMIT 3";
        record ScoredId(long id, double distance) { }
        try (var connection = connect(); var query = connection.prepareStatement(sql)) {
            var best = new ArrayList<ScoredId>();
            String questionVector = vector(question);
            for (int offset = 0; offset < eligibleIds.size(); offset += SEARCH_BATCH_SIZE) {
                var batch = eligibleIds.subList(offset,
                        Math.min(offset + SEARCH_BATCH_SIZE, eligibleIds.size()));
                query.setLong(1, groupId);
                query.setString(2, model);
                query.setArray(3, connection.createArrayOf("bigint", batch.toArray(Long[]::new)));
                query.setString(4, questionVector);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) best.add(new ScoredId(rows.getLong(1), rows.getDouble(2)));
                }
            }
            return best.stream().sorted(Comparator.comparingDouble(ScoredId::distance)
                    .thenComparingLong(ScoredId::id)).limit(3).map(ScoredId::id).toList();
        } catch (SQLException e) {
            throw new IllegalStateException("群聊向量检索失败", e);
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
        if (values.length != 1024) throw new IllegalArgumentException("向量维度必须为 1024（bge-m3）");
        var result = new StringBuilder(8192).append('[');
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("向量包含无效数值");
            if (i > 0) result.append(',');
            result.append(values[i]);
        }
        return result.append(']').toString();
    }
}
