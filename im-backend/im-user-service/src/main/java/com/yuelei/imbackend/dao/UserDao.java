package com.yuelei.imbackend.dao;

import com.yuelei.imbackend.model.NewUser;
import com.yuelei.imbackend.model.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserDao {
    UserAccount byPhone(@Param("phone") String phone);

    UserAccount byEmail(@Param("email") String email);

    UserAccount byId(@Param("id") long id);

    long countByHandle(@Param("handle") String handle);

    long countByPhone(@Param("phone") String phone);

    long countByEmail(@Param("email") String email);

    int create(NewUser user);

    int bindPhone(@Param("id") long id, @Param("phone") String phone);

    int bindEmail(@Param("id") long id, @Param("email") String email);

    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);
}
