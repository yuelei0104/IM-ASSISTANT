package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.DirectMessage;
import com.yuelei.imchat.model.UnreadCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageDao {
    int ensurePair(@Param("low") long low, @Param("high") long high);
    int lockPair(@Param("low") long low, @Param("high") long high);
    int insert(@Param("low") long low, @Param("high") long high,
               @Param("senderId") long senderId, @Param("recipientId") long recipientId,
               @Param("clientMsgId") String clientMsgId, @Param("body") String body);
    int trackInbox(@Param("userId") long userId, @Param("peerId") long peerId);
    DirectMessage byClientId(@Param("senderId") long senderId, @Param("clientMsgId") String clientMsgId);
    List<DirectMessage> history(@Param("low") long low, @Param("high") long high,
                                @Param("beforeId") Long beforeId, @Param("limit") int limit);
    List<DirectMessage> search(@Param("low") long low, @Param("high") long high,
                               @Param("pattern") String pattern, @Param("beforeId") Long beforeId,
                               @Param("limit") int limit);
    List<DirectMessage> after(@Param("low") long low, @Param("high") long high,
                              @Param("afterId") long afterId, @Param("limit") int limit);
    List<UnreadCount> unreadCounts(@Param("userId") long userId);
    long receivedCount(@Param("userId") long userId, @Param("peerId") long peerId,
                       @Param("messageId") long messageId);
    int markRead(@Param("userId") long userId, @Param("peerId") long peerId,
                 @Param("messageId") long messageId);
}
