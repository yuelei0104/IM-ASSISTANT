package com.yuelei.imbackend.model;

/** Insert parameters; MyBatis fills the generated id after INSERT. */
public class NewUser {
    private Long id;
    private final String nickname;
    private final String handle;
    private final String phone;
    private final String email;
    private final String passwordHash;

    public NewUser(String nickname, String handle, String phone, String email, String passwordHash) {
        this.nickname = nickname;
        this.handle = handle;
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNickname() { return nickname; }
    public String getHandle() { return handle; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
}
