package com.yuelei.imchat.service;

import java.util.List;

public record MentionEvent(List<Long> recipients) { }
