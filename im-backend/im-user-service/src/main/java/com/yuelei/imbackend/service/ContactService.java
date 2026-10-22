package com.yuelei.imbackend.service;

import com.yuelei.imbackend.dao.ContactDao;
import com.yuelei.imbackend.dao.UserDao;
import com.yuelei.imbackend.dto.ContactDtos.Invite;
import com.yuelei.imbackend.dto.ContactPage;
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
    private final InvitationRateLimiter inviteLimits;

    public ContactService(ContactDao contacts, UserDao users, SessionService sessions, InvitationRateLimiter inviteLimits) {
        this.contacts = contacts;
        this.users = users;
        this.sessions = sessions;
        this.inviteLimits = inviteLimits;
    }

    public ContactPage<ContactProfile> friends(String authorization, Long afterId, int limit) {
        validatePage(afterId, limit);
        return page(contacts.friends(sessions.requireUserId(authorization), afterId == null ? 0 : afterId, limit + 1), limit,
                ContactProfile::id);
    }

    public long requireFriend(String authorization, long friendId) {
        long me = sessions.requireUserId(authorization);
        if (friendId <= 0 || friendId == me ||
                contacts.friendshipCount(Math.min(me, friendId), Math.max(me, friendId)) == 0)
            throw new AuthException(HttpStatus.FORBIDDEN, "只有好友之间可以发送消息");
        return me;
    }

    public ContactProfile friendProfile(String authorization, long friendId) {
        requireFriend(authorization, friendId);
        UserAccount friend = users.byId(friendId);
        if (friend == null) throw new AuthException(HttpStatus.NOT_FOUND, "好友账号不存在");
        return new ContactProfile(friend.id(), friend.nickname(), friend.handle());
    }

    public ContactPage<FriendRequestView> requests(String authorization, Long beforeId, int limit) {
        validatePage(beforeId, limit);
        return page(contacts.requests(sessions.requireUserId(authorization), beforeId == null ? Long.MAX_VALUE : beforeId, limit + 1), limit,
                FriendRequestView::id);
    }

    @Transactional
    public void invite(String authorization, Invite input) {
        long me = sessions.requireUserId(authorization);
        boolean hasHandle = input.handle() != null && !input.handle().isBlank();
        boolean hasPhone = input.phone() != null && !input.phone().isBlank();
        if (hasHandle == hasPhone)
            throw new AuthException(HttpStatus.BAD_REQUEST, "请填写手机号或唯一账号，两者只需一项");
        String normalized = hasPhone ? Contacts.normalize(Type.PHONE, input.phone()) : Contacts.handle(input.handle());
        inviteLimits.check(me);
        UserAccount target = hasPhone
                ? users.byPhone(normalized) : users.byHandle(normalized);
        // Keep the response identical for unknown, self, already-friends and duplicate invitations.
        if (target == null || target.id() == me) return;
        long low = Math.min(me, target.id()), high = Math.max(me, target.id());
        if (contacts.friendshipCount(low, high) > 0) return;
        String note = input.note() == null || input.note().isBlank()
                ? "你好，想加你为好友。" : input.note().trim();
        try {
            contacts.invite(me, target.id(), note);
        } catch (DataIntegrityViolationException ex) {
            // Another pending request (or a concurrent friendship) has the same public result.
        }
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

    private static void validatePage(Long cursor, int limit) {
        if (cursor != null && cursor <= 0 || limit < 1 || limit > 50)
            throw new AuthException(HttpStatus.BAD_REQUEST, "分页参数无效");
    }

    private static <T> ContactPage<T> page(List<T> rows, int limit, java.util.function.ToLongFunction<T> id) {
        List<T> items = rows.subList(0, Math.min(rows.size(), limit));
        return new ContactPage<>(items, rows.size() > limit ? id.applyAsLong(items.get(items.size() - 1)) : null);
    }
}
