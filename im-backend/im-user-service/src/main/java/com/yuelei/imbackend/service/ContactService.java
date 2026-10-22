package com.yuelei.imbackend.service;

import com.yuelei.imbackend.dao.ContactDao;
import com.yuelei.imbackend.dao.UserDao;
import com.yuelei.imbackend.dto.ContactDtos.Invite;
import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.model.ContactProfile;
import com.yuelei.imbackend.model.FriendRequestRow;
import com.yuelei.imbackend.model.FriendRequestView;
import com.yuelei.imbackend.model.UserAccount;
import com.yuelei.imbackend.util.Contacts;
import com.yuelei.imbackend.util.Contacts.Type;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ContactService {
    private final ContactDao contacts;
    private final UserDao users;
    private final SessionService sessions;

    public ContactService(ContactDao contacts, UserDao users, SessionService sessions) {
        this.contacts = contacts;
        this.users = users;
        this.sessions = sessions;
    }

    public ContactProfile find(String authorization, String rawHandle) {
        sessions.requireUserId(authorization);
        UserAccount account = users.byHandle(Contacts.handle(rawHandle));
        if (account == null) throw new AuthException(HttpStatus.NOT_FOUND, "未找到该账号");
        return profile(account);
    }

    public ContactProfile findByPhone(String authorization, String rawPhone) {
        sessions.requireUserId(authorization);
        UserAccount account = users.byPhone(Contacts.normalize(Type.PHONE, rawPhone));
        if (account == null) throw new AuthException(HttpStatus.NOT_FOUND, "未找到该手机号绑定的账号");
        return profile(account);
    }

    public List<ContactProfile> friends(String authorization) {
        return contacts.friends(sessions.requireUserId(authorization));
    }

    public List<FriendRequestView> requests(String authorization) {
        return contacts.requests(sessions.requireUserId(authorization));
    }

    @Transactional
    public void invite(String authorization, Invite input) {
        long me = sessions.requireUserId(authorization);
        boolean hasHandle = input.handle() != null && !input.handle().isBlank();
        boolean hasPhone = input.phone() != null && !input.phone().isBlank();
        if (hasHandle == hasPhone)
            throw new AuthException(HttpStatus.BAD_REQUEST, "请填写手机号或唯一账号，两者只需一项");
        UserAccount target = hasPhone
                ? users.byPhone(Contacts.normalize(Type.PHONE, input.phone()))
                : users.byHandle(Contacts.handle(input.handle()));
        if (target == null) throw new AuthException(HttpStatus.NOT_FOUND, "未找到该账号");
        if (target.id() == me) throw new AuthException(HttpStatus.BAD_REQUEST, "不能添加自己为好友");
        long low = Math.min(me, target.id()), high = Math.max(me, target.id());
        if (contacts.friendshipCount(low, high) > 0)
            throw new AuthException(HttpStatus.CONFLICT, "你们已经是好友");
        String note = input.note() == null || input.note().isBlank()
                ? "你好，想加你为好友。" : input.note().trim();
        try {
            contacts.invite(me, target.id(), note);
        } catch (DataIntegrityViolationException ex) {
            throw new AuthException(HttpStatus.CONFLICT, "你们已有待处理的申请");
        }
        if (contacts.friendshipCount(low, high) > 0)
            throw new AuthException(HttpStatus.CONFLICT, "你们已经是好友");
    }

    @Transactional
    public void resolve(String authorization, long id, String action) {
        long me = sessions.requireUserId(authorization);
        FriendRequestRow request = contacts.requestForUpdate(id);
        if (request == null) throw new AuthException(HttpStatus.NOT_FOUND, "申请不存在");
        if (!"PENDING".equals(request.state()))
            throw new AuthException(HttpStatus.CONFLICT, "申请已处理，请刷新列表");
        if ("WITHDRAWN".equals(action) ? request.fromId() != me : request.toId() != me)
            throw new AuthException(HttpStatus.FORBIDDEN, "无权处理此申请");
        if ("ACCEPTED".equals(action)) {
            contacts.addFriendship(Math.min(request.fromId(), request.toId()),
                    Math.max(request.fromId(), request.toId()));
        }
        if (contacts.updateRequest(id, action) != 1)
            throw new AuthException(HttpStatus.CONFLICT, "申请已处理，请刷新列表");
    }

    @Transactional
    public void remove(String authorization, long friendId) {
        long me = sessions.requireUserId(authorization);
        if (me == friendId || friendId <= 0
                || contacts.removeFriendship(Math.min(me, friendId), Math.max(me, friendId)) != 1)
            throw new AuthException(HttpStatus.NOT_FOUND, "好友关系不存在");
    }

    private static ContactProfile profile(UserAccount account) {
        return new ContactProfile(account.id(), account.nickname(), account.handle());
    }
}
