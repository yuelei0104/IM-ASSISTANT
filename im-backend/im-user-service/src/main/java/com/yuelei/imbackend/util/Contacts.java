package com.yuelei.imbackend.util;

import com.yuelei.imbackend.exception.AuthException;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Contacts {
    private static final Pattern PHONE = Pattern.compile("1[3-9][0-9]{9}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern HANDLE = Pattern.compile("[a-z0-9_]{3,20}");

    private Contacts() { }

    public enum Type { PHONE, EMAIL }
    public enum Purpose { REGISTER, LOGIN, BIND, RESET }

    public static Type type(String value) {
        try {
            return Type.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (NullPointerException | IllegalArgumentException ex) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "联系方式类型只能是 phone 或 email");
        }
    }

    public static Purpose purpose(String value) {
        try {
            return Purpose.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (NullPointerException | IllegalArgumentException ex) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "验证码用途无效");
        }
    }

    public static String normalize(Type type, String value) {
        String contact = value == null ? "" : value.trim();
        contact = type == Type.EMAIL ? contact.toLowerCase(Locale.ROOT)
                : contact.replace(" ", "").replace("-", "");
        boolean valid = type == Type.PHONE ? PHONE.matcher(contact).matches()
                : contact.length() <= 254 && EMAIL.matcher(contact).matches();
        if (!valid) throw new AuthException(HttpStatus.BAD_REQUEST, "手机号或邮箱格式不正确");
        return contact;
    }

    public static String optional(Type type, String value) {
        return value == null || value.isBlank() ? null : normalize(type, value);
    }

    public static String handle(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!HANDLE.matcher(normalized).matches())
            throw new AuthException(HttpStatus.BAD_REQUEST, "账号需为 3–20 位字母、数字或下划线");
        return normalized;
    }

    public static String nickname(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 24)
            throw new AuthException(HttpStatus.BAD_REQUEST, "昵称应为 1–24 个字符");
        return normalized;
    }

    public static void password(String value) {
        if (value == null || value.length() < 8 || value.length() > 72
                || !value.matches(".*[A-Za-z].*") || !value.matches(".*[0-9].*"))
            throw new AuthException(HttpStatus.BAD_REQUEST, "密码需为 8–72 位，并包含字母和数字");
    }
}
