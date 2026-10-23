package com.yuelei.imchat.service;

import com.yuelei.imchat.exception.ChatException;
import org.springframework.http.HttpStatus;

final class SearchTerm {
    private SearchTerm() { }

    static String pattern(String query) {
        String value = query == null ? "" : query.trim();
        if (value.length() < 2 || value.length() > 50)
            throw new ChatException(HttpStatus.BAD_REQUEST, "搜索词应为 2 到 50 个字符");
        // '=' is the SQL LIKE escape character, so user-supplied wildcards remain literal.
        return value.replace("=", "==").replace("%", "=%").replace("_", "=_");
    }
}
