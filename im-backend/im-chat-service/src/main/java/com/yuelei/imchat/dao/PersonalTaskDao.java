package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.PersonalTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PersonalTaskDao {
    class Draft {
        private Long id;
        private final long ownerId;
        private final String title;

        public Draft(long ownerId, String title) {
            this.ownerId = ownerId;
            this.title = title;
        }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getOwnerId() { return ownerId; }
        public String getTitle() { return title; }
    }

    int insert(Draft draft);
    PersonalTask owned(@Param("ownerId") long ownerId, @Param("id") long id);
    List<PersonalTask> list(@Param("ownerId") long ownerId, @Param("beforeId") Long beforeId,
                            @Param("limit") int limit);
    long countOpen(@Param("ownerId") long ownerId);
    long countCompleted(@Param("ownerId") long ownerId);
    int setCompleted(@Param("ownerId") long ownerId, @Param("id") long id,
                     @Param("completed") boolean completed);
    int delete(@Param("ownerId") long ownerId, @Param("id") long id);
}
