package com.yuelei.imchat.service;

import com.yuelei.imchat.model.GroupMessage;
import java.util.List;

public interface SummaryGenerator {
    String summarize(List<GroupMessage> messages);
}
