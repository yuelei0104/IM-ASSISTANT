package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.DirectMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageDao {
    int insert(@Param("low") long low, @Param("high") long high,
               @Param("senderId") long senderId, @Param("recipientId") long recipientId,
               @Param("clientMsgId") String clientMsgId, @Param("body") String body);
    DirectMessage byClientId(@Param("senderId") long senderId, @Param("clientMsgId") String clientMsgId);
    List<DirectMessage> history(@Param("low") long low, @Param("high") long high,
                                @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
