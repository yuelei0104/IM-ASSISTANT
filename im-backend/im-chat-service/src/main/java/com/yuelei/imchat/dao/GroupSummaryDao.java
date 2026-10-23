package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.GroupSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GroupSummaryDao {
    class Draft {
        private Long id;
        private final long groupId;
        private final long requesterId;
        private final long firstMessageId;
        private final long lastMessageId;
        private final String content;

        public Draft(long groupId, long requesterId, long firstMessageId,
                     long lastMessageId, String content) {
            this.groupId = groupId;
            this.requesterId = requesterId;
            this.firstMessageId = firstMessageId;
            this.lastMessageId = lastMessageId;
            this.content = content;
        }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getGroupId() { return groupId; }
        public long getRequesterId() { return requesterId; }
        public long getFirstMessageId() { return firstMessageId; }
        public long getLastMessageId() { return lastMessageId; }
        public String getContent() { return content; }
    }

    int insert(Draft draft);
    GroupSummary visibleById(@Param("id") long id, @Param("groupId") long groupId,
                             @Param("userId") long userId);
    List<GroupSummary> visibleList(@Param("groupId") long groupId,
                                   @Param("userId") long userId, @Param("limit") int limit);
}
