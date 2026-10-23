CREATE TABLE im_personal_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_personal_task_owner_id (owner_id, id),
    KEY idx_personal_task_owner_completed (owner_id, completed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
