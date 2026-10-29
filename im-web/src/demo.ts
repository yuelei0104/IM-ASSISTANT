export type Person = {
  id: string;
  name: string;
  handle: string;
  initials: string;
  color: string;
  status: "online" | "away" | "offline";
  note: string;
};

export type Message = {
  id: string;
  conversationId: string;
  senderId: string;
  text: string;
  time: string;
  kind: "text" | "voice" | "system";
  translation?: string;
  status?: "local" | "failed";
  recalled?: boolean;
  createdAt?: string;
  readReceipt?: "已发送" | "对方已读";
};

export type Conversation = {
  id: string;
  name: string;
  kind: "group" | "direct";
  memberIds: string[];
  ownerId?: string;
  description?: string;
  unread: number;
  muted?: boolean;
  pinned?: boolean;
  color: string;
  initials: string;
};

export type FriendRequest = {
  id: string;
  fromId: string;
  toId: string;
  note: string;
  time: string;
  state: "pending" | "accepted" | "rejected" | "withdrawn";
};

export type Todo = {
  id: string;
  title: string;
  due: string;
  done: boolean;
  sourceId?: string;
  sourceConversationId?: string;
};

export type Notice = {
  id: string;
  title: string;
  description: string;
  time: string;
  read: boolean;
  sourceId?: string;
  sourceConversationId?: string;
  type: "mention" | "activity" | "system";
};

export type Preferences = {
  fontSize: 16 | 18 | 20;
  focusWords: string;
  quietHours: boolean;
  translationTarget: "en" | "zh";
  showTranslations: boolean;
  timezone: string;
};

export type DemoState = {
  people: Person[];
  friendIds: string[];
  conversations: Conversation[];
  messages: Message[];
  requests: FriendRequest[];
  todos: Todo[];
  notices: Notice[];
  preferences: Preferences;
  displayName: string;
  handle: string;
};

export const selfId = "me";

export function initialState(): DemoState {
  return {
    people: [
      {
        id: "me",
        name: "岳蕾",
        handle: "yuelei",
        initials: "岳",
        color: "violet",
        status: "online",
        note: "保持对话，也不遗漏重要的事。",
      },
      {
        id: "lin",
        name: "林知夏",
        handle: "linzhixia",
        initials: "夏",
        color: "peach",
        status: "online",
        note: "产品设计 · 关注每一个细节",
      },
      {
        id: "zhou",
        name: "周一诺",
        handle: "zhouyinuo",
        initials: "诺",
        color: "blue",
        status: "online",
        note: "前端开发 · 正在整理页面",
      },
      {
        id: "chen",
        name: "陈嘉树",
        handle: "chenjiashu",
        initials: "陈",
        color: "rose",
        status: "away",
        note: "服务端开发 · 稍后回复",
      },
      {
        id: "xu",
        name: "徐星遥",
        handle: "xuxingyao",
        initials: "遥",
        color: "gold",
        status: "offline",
        note: "喜欢清晰的沟通",
      },
      {
        id: "he",
        name: "何雨桐",
        handle: "heyutong",
        initials: "桐",
        color: "lilac",
        status: "online",
        note: "新加入的同学",
      },
    ],
    friendIds: ["lin", "zhou", "chen", "xu"],
    conversations: [
      {
        id: "product",
        name: "产品共创讨论室",
        kind: "group",
        memberIds: ["me", "lin", "zhou", "chen"],
        ownerId: "me",
        description: "把讨论变成可以落实的下一步。",
        unread: 3,
        pinned: true,
        color: "indigo",
        initials: "产",
      },
      {
        id: "lin-chat",
        name: "林知夏",
        kind: "direct",
        memberIds: ["me", "lin"],
        unread: 1,
        color: "peach",
        initials: "夏",
      },
      {
        id: "engineering",
        name: "研发协作",
        kind: "group",
        memberIds: ["me", "zhou", "chen", "xu"],
        ownerId: "zhou",
        description: "同步开发进展、解决阻塞。",
        unread: 0,
        color: "teal",
        initials: "研",
      },
      {
        id: "zhou-chat",
        name: "周一诺",
        kind: "direct",
        memberIds: ["me", "zhou"],
        unread: 0,
        color: "blue",
        initials: "诺",
      },
    ],
    messages: [
      {
        id: "p1",
        conversationId: "product",
        senderId: "lin",
        text: "早上好！今天我们把讨论室的体验再过一遍，尤其是新成员加入后的消息可见范围。",
        time: "09:24",
        kind: "text",
      },
      {
        id: "p2",
        conversationId: "product",
        senderId: "me",
        text: "好，我先把好友申请和拉群流程整理成一个可操作的原型。",
        time: "09:28",
        kind: "text",
      },
      {
        id: "p3",
        conversationId: "product",
        senderId: "chen",
        text: "接口截止时间我们先定在周五，今晚我会发一版字段说明。",
        time: "09:32",
        kind: "text",
      },
      {
        id: "p4",
        conversationId: "product",
        senderId: "lin",
        text: "新成员只看加入后的消息。群主可以从自己的好友里直接添加，最多 50 人。",
        time: "09:36",
        kind: "text",
      },
      {
        id: "p5",
        conversationId: "product",
        senderId: "zhou",
        text: "我会补上移动端的会话列表，还有断线后的状态提示。",
        time: "10:08",
        kind: "text",
      },
      {
        id: "p6",
        conversationId: "product",
        senderId: "chen",
        text: "更正一下：接口截止时间改到下周一，周五先完成登录和好友接口。",
        time: "10:16",
        kind: "text",
      },
      {
        id: "p7",
        conversationId: "product",
        senderId: "lin",
        text: "@岳蕾 你方便把语音转写入口也放到输入框旁边吗？字幕先只给说话的人看。",
        time: "10:21",
        kind: "text",
      },
      {
        id: "l1",
        conversationId: "lin-chat",
        senderId: "lin",
        text: "你发的布局稿我看到了，三栏的节奏很好。晚点一起核一下空状态？",
        time: "昨天 18:42",
        kind: "text",
      },
      {
        id: "l2",
        conversationId: "lin-chat",
        senderId: "me",
        text: "可以，我会把通知和待办页面也补齐。",
        time: "昨天 18:46",
        kind: "text",
      },
      {
        id: "l3",
        conversationId: "lin-chat",
        senderId: "lin",
        text: "好的，明天见！",
        time: "昨天 18:48",
        kind: "text",
      },
      {
        id: "e1",
        conversationId: "engineering",
        senderId: "zhou",
        text: "我把前端拆成了清晰的页面结构，今天会跑一次完整构建。",
        time: "昨天 16:30",
        kind: "text",
      },
      {
        id: "e2",
        conversationId: "engineering",
        senderId: "xu",
        text: "The review is scheduled for Monday.",
        time: "昨天 16:35",
        kind: "voice",
        translation: "评审安排在周一。",
      },
      {
        id: "z1",
        conversationId: "zhou-chat",
        senderId: "zhou",
        text: "有空帮我看下新会话的空状态吗？",
        time: "周二 14:08",
        kind: "text",
      },
    ],
    requests: [
      {
        id: "r1",
        fromId: "he",
        toId: "me",
        note: "你好！我们在产品共创会上见过，想交流一下前端设计。",
        time: "今天 10:12",
        state: "pending",
      },
    ],
    todos: [],
    notices: [
      {
        id: "n1",
        title: "林知夏提到了你",
        description: "你方便把语音转写入口也放到输入框旁边吗？",
        time: "10:21",
        read: false,
        sourceId: "p7",
        sourceConversationId: "product",
        type: "mention",
      },
      {
        id: "n2",
        title: "接口时间有更新",
        description: "截止时间由周五改到下周一，请以最新消息为准。",
        time: "10:16",
        read: false,
        sourceId: "p6",
        sourceConversationId: "product",
        type: "activity",
      },
      {
        id: "n3",
        title: "欢迎来到 IM 助手",
        description: "在这里把消息、讨论和待办放在同一个工作空间。",
        time: "昨天",
        read: true,
        type: "system",
      },
    ],
    preferences: {
      fontSize: 16,
      focusWords: "岳蕾, 截止时间, 接口",
      quietHours: false,
      translationTarget: "zh",
      showTranslations: true,
      timezone: "Asia/Shanghai",
    },
    displayName: "岳蕾",
    handle: "yuelei",
  };
}

export function uid(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function clock(): string {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date());
}
