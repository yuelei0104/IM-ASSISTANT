-- A task is a user's confirmed note. References are optional and intentionally
-- have no foreign key: dissolving a group must not delete personal tasks.
ALTER TABLE im_personal_task
    ADD COLUMN source_group_id BIGINT NULL,
    ADD COLUMN source_message_id BIGINT NULL;
