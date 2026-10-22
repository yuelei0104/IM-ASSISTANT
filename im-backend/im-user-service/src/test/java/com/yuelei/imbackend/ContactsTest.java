package com.yuelei.imbackend;

import com.yuelei.imbackend.exception.AuthException;
import com.yuelei.imbackend.util.Contacts;
import com.yuelei.imbackend.util.Contacts.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContactsTest {
    @Test
    void normalizesEmailAndPhoneButRejectsInvalidInputs() {
        assertEquals("abc@example.com", Contacts.normalize(Type.EMAIL, " ABC@Example.com "));
        assertEquals("13800138000", Contacts.normalize(Type.PHONE, "138 0013 8000"));
        assertThrows(AuthException.class, () -> Contacts.normalize(Type.PHONE, "12345"));
        assertThrows(AuthException.class, () -> Contacts.password("abcdefgh"));
    }
}
