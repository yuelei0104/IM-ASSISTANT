package com.yuelei.imbackend.dto;

import java.util.List;

public record ContactPage<T>(List<T> items, Long nextCursor) { }
