CREATE TABLE im_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nickname VARCHAR(24) NOT NULL,
    handle VARCHAR(20) NOT NULL,
    phone VARCHAR(20) NULL,
    email VARCHAR(254) NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_user_handle (handle),
    UNIQUE KEY uk_im_user_phone (phone),
    UNIQUE KEY uk_im_user_email (email),
    CONSTRAINT chk_im_user_contact CHECK (phone IS NOT NULL OR email IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
