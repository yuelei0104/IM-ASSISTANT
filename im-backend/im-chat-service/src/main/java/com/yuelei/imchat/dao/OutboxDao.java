package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.OutboxRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OutboxDao {
    int insert(@Param("eventKey") String eventKey, @Param("payload") String payload);
    Integer tryLock();
    Integer releaseLock();
    List<OutboxRow> pending(@Param("limit") int limit);
    int markPublished(@Param("id") long id);
    int deletePublished(@Param("limit") int limit);
}
