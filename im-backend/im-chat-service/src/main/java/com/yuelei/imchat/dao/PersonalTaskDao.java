package com.yuelei.imchat.dao;

import com.yuelei.imchat.model.PersonalTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDate;

@Mapper
public interface PersonalTaskDao {
    class Draft {
        private Long id;
        private final long ownerId;
        private final String title;
        private final Long sourceGroupId;
        private final Long sourceMessageId;
        private final LocalDate dueDate;

        public Draft(long ownerId, String title, Long sourceGroupId, Long sourceMessageId, LocalDate dueDate) {
            this.ownerId = ownerId;
            this.title = title;
            this.sourceGroupId = sourceGroupId;
            this.sourceMessageId = sourceMessageId;
            this.dueDate = dueDate;
        }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getOwnerId() { return ownerId; }
        public String getTitle() { return title; }
        public Long getSourceGroupId() { return sourceGroupId; }
        public Long getSourceMessageId() { return sourceMessageId; }
        public LocalDate getDueDate() { return dueDate; }
    }

    int insert(Draft draft);
    PersonalTask owned(@Param("ownerId") long ownerId, @Param("id") long id);
    List<PersonalTask> list(@Param("ownerId") long ownerId, @Param("beforeId") Long beforeId,
                            @Param("limit") int limit);
    long countOpen(@Param("ownerId") long ownerId);
    long countCompleted(@Param("ownerId") long ownerId);
    int setCompleted(@Param("ownerId") long ownerId, @Param("id") long id,
                     @Param("completed") boolean completed);
    int updateDetails(@Param("ownerId") long ownerId, @Param("id") long id,
                      @Param("title") String title, @Param("dueDate") LocalDate dueDate);
    int delete(@Param("ownerId") long ownerId, @Param("id") long id);
}
