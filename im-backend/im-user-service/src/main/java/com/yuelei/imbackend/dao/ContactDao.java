package com.yuelei.imbackend.dao;

import com.yuelei.imbackend.model.ContactProfile;
import com.yuelei.imbackend.model.FriendRequestRow;
import com.yuelei.imbackend.model.FriendRequestView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ContactDao {
    List<ContactProfile> friends(@Param("userId") long userId);
    List<FriendRequestView> requests(@Param("userId") long userId);
    FriendRequestRow requestForUpdate(@Param("id") long id);
    long friendshipCount(@Param("low") long low, @Param("high") long high);
    int invite(@Param("fromId") long fromId, @Param("toId") long toId, @Param("note") String note);
    int updateRequest(@Param("id") long id, @Param("state") String state);
    int addFriendship(@Param("low") long low, @Param("high") long high);
    int removeFriendship(@Param("low") long low, @Param("high") long high);
}
