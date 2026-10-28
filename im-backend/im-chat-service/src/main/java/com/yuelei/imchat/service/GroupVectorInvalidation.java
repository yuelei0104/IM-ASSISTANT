package com.yuelei.imchat.service;

/** Remove derived vectors after the corresponding MySQL change commits. */
public record GroupVectorInvalidation(long groupId, Long messageId) {
    public static GroupVectorInvalidation group(long groupId) { return new GroupVectorInvalidation(groupId, null); }
    public static GroupVectorInvalidation message(long groupId, long messageId) {
        return new GroupVectorInvalidation(groupId, messageId);
    }
}
