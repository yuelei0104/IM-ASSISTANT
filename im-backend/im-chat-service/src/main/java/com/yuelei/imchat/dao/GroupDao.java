package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.GroupMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GroupDao {
    record GroupRow(long id, String name, long ownerId, long unreadCount) { }
    record Membership(long joinedAfterId, long lastReadId) { }

    // The generated key is assigned to the bean by MyBatis.
    class NewGroup {
        private Long id;
        private String name;
        private long ownerId;
        public NewGroup(String name, long ownerId) { this.name = name; this.ownerId = ownerId; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public long getOwnerId() { return ownerId; }
    }
    int insertGroup(NewGroup group);
    GroupRow group(@Param("groupId") long groupId);
    GroupRow lockGroup(@Param("groupId") long groupId);
    List<GroupRow> groups(@Param("userId") long userId, @Param("beforeId") Long beforeId,
                          @Param("limit") int limit);
    List<Long> memberIds(@Param("groupId") long groupId);
    Membership membership(@Param("groupId") long groupId, @Param("userId") long userId);
    int addMember(@Param("groupId") long groupId, @Param("userId") long userId,
                  @Param("cursor") long cursor);
    Long latestId(@Param("groupId") long groupId);
    int insertMessage(@Param("groupId") long groupId, @Param("senderId") long senderId,
                      @Param("clientMsgId") String clientMsgId, @Param("body") String body);
    GroupMessage byClientId(@Param("senderId") long senderId, @Param("clientMsgId") String clientMsgId);
    GroupMessage messageById(@Param("groupId") long groupId, @Param("messageId") long messageId,
                             @Param("joinedAfterId") long joinedAfterId);
    List<GroupMessage> history(@Param("groupId") long groupId, @Param("joinedAfterId") long joinedAfterId,
                               @Param("beforeId") Long beforeId, @Param("limit") int limit);
    List<GroupMessage> search(@Param("groupId") long groupId, @Param("joinedAfterId") long joinedAfterId,
                              @Param("pattern") String pattern, @Param("beforeId") Long beforeId,
                              @Param("limit") int limit);
    List<GroupMessage> after(@Param("groupId") long groupId, @Param("joinedAfterId") long joinedAfterId,
                             @Param("afterId") long afterId, @Param("limit") int limit);
    Long validMessage(@Param("groupId") long groupId, @Param("messageId") long messageId,
                      @Param("joinedAfterId") long joinedAfterId);
    int markRead(@Param("groupId") long groupId, @Param("userId") long userId,
                 @Param("messageId") long messageId);
}
