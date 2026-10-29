package com.yuelei.imchat;

import com.yuelei.imchat.service.GroupVectorStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroupVectorStoreTest {
    @Test
    void deploymentHostIsLimitedToLocalOrPrivateService() {
        assertDoesNotThrow(() -> new GroupVectorStore("jdbc:postgresql://pgvector:5432/im_vectors", "im_vector", "x"));
        assertDoesNotThrow(() -> new GroupVectorStore("jdbc:postgresql://127.0.0.1:15432/im_vectors", "im_vector", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new GroupVectorStore("jdbc:postgresql://example.com:5432/im_vectors", "im_vector", "x"));
    }

    @Test
    void hashesOnlyTheAuthorizedTextAndRequiresTheConfiguredEmbeddingDimension() {
        assertEquals(64, GroupVectorStore.hash("会议结论").length());
        assertNotEquals(GroupVectorStore.hash("会议结论"), GroupVectorStore.hash("已撤回"));
        assertThrows(IllegalArgumentException.class, () -> GroupVectorStore.vector(new float[10]));
        float[] vector = new float[1024];
        vector[0] = 0.5f;
        assertTrue(GroupVectorStore.vector(vector).startsWith("[0.5,0.0,"));
        vector[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> GroupVectorStore.vector(vector));
    }
}
