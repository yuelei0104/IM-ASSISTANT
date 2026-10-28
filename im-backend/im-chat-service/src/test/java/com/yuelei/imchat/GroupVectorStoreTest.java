package com.yuelei.imchat;

import com.yuelei.imchat.service.GroupVectorStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroupVectorStoreTest {
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
