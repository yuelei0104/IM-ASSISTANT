#!/usr/bin/env python3
"""Seed a private local IM demo: four members, one outsider, and 136 meeting messages.

Run only against the development user/chat services. The ignored local state
holds synthetic phone numbers and random passwords used internally for
idempotent registration. Sign in on the web with phone + development code.
Re-running resumes the same fixture; stable clientMsgIds prevent duplicates.
"""

import argparse
import json
import os
import secrets
import sys
import uuid
from pathlib import Path
from urllib.parse import urlencode

from group_privacy_probe import ProbeFailure, call, check, expect, loopback_url


STATE = Path(__file__).resolve().parents[1] / ".local-stack" / "demo-meeting.json"
PEOPLE = (
    ("林岚", "产品负责人"),
    ("陈澈", "后端开发"),
    ("周予", "前端开发"),
    ("许宁", "测试与无障碍"),
    ("顾远", "非群成员"),
)

# Each round is a short exchange between product, backend, frontend, and QA.
# The text is synthetic and describes a fictional release, not a real meeting.
SECTIONS = (
    ("需求范围", (
        ("这次评审聚焦协同办公 v0.9 的聊天、讨论室和会议待办。", "先明确最小闭环：账号、好友、群消息和断线补拉。", "我会把登录、申请好友和讨论室放进同一条演示路径。", "验收先走四个账号，覆盖创建、邀请、退出和无权限访问。"),
        ("实时字幕只允许用户确认后分享，不做自动外发。", "字幕入库仍走群消息权限，退出成员不再能查询历史。", "确认按钮要写清楚分享范围，发送前可预览文字。", "我会检查误触关闭后是否真的没有产生群消息。"),
        ("会议摘要要说明议题、结论和待办，还要能点开来源。", "摘要来源保存消息 ID，消息被撤回后应失效。", "来源面板显示原文和发送人，不展示群外内容。", "缺少依据时模型应直说无法判断，不能编造结论。"),
        ("本次不加入审批流和支付，优先保证消息可用。", "需求冻结到周三 18:00，之后只处理阻断问题。", "我会标记未完成入口，避免演示时误点到空白页。", "测试记录会把需求、通过条件和缺陷编号关联起来。"),
    )),
    ("账号与权限", (
        ("注册先做联系方式验证，再创建账户。", "测试环境验证码回显，正式环境必须接邮件或短信服务。", "登录首页区分密码和验证码，错误信息提示下一步操作。", "我会用不存在的联系方式检查是否出现误导性提示。"),
        ("好友申请只能对已注册用户发送。", "邀请接口要校验目标身份，并对重复请求做幂等处理。", "好友列表和申请列表都支持分页，空列表文案要明确。", "我会模拟两个人同时邀请，确认不会留下两条待处理记录。"),
        ("群主才能邀请、移除、转让和解散讨论室。", "权限判断在服务端完成，前端按钮隐藏只是辅助。", "转让前会有二次确认，解散后退出当前讨论室。", "群外用户查详情、消息和摘要都应该被拒绝。"),
        ("新成员进群后不能补看加入前的会议内容。", "成员表记录加入游标，历史、搜索和 Agent 检索共用边界。", "进群提示会说明可见范围从加入时开始。", "退出后重新加入也不能找回之前那段不可见消息。"),
    )),
    ("消息可靠性", (
        ("发送按钮需要有结果反馈，失败不能让人以为已送达。", "客户端消息 ID 加数据库唯一约束，超时重试返回同一条记录。", "待发送气泡显示状态，重试使用原编号。", "我会故意断网后重连，核对是否重复显示。"),
        ("两台聊天节点要能把消息推给不同节点上的用户。", "Redis 记录用户到节点的路由，节点下线再走兜底同步。", "WebSocket 重连后按游标补拉，不依赖单次推送成功。", "测试会切断一台节点，检查对端是否还能读到消息。"),
        ("未读数字要在会话列表及时变化。", "标记已读要用持久化游标，重登后继续保持状态。", "切换会话时刷新未读，当前会话避免重复红点。", "我会用两台浏览器检查未读数与实际消息一致。"),
        ("撤回消息后不要让搜索和提醒继续暴露原文。", "撤回写入状态并发变更事件，列表用占位内容。", "界面保留‘消息已撤回’位置，避免时间线跳动。", "直接查 ID、搜索和摘要来源都要一起验收。"),
    )),
    ("无障碍体验", (
        ("远程协作会议先保证文字信息完整。", "字幕只存最终确认文本，不把未确认的中间识别结果落库。", "字幕区域增加大字号和高对比度开关。", "我要用键盘走通输入、发送、切换讨论室全流程。"),
        ("点名提醒要清楚，但不能盖住正在看的消息。", "@提醒只发给当前成员，移除后清理相关提醒。", "提醒卡片包含发送者、群名和定位入口。", "读屏时提醒文字应能被读出，不依赖颜色区分。"),
        ("中英翻译是辅助阅读，原文必须始终可见。", "翻译请求带群成员身份，不允许跨群读取原文。", "译文在原文下方展开，失败时保留原文。", "我会检查长句换行、缩放到 200% 和窄屏布局。"),
        ("不录音的用户也能参与会议。", "文本输入和群聊发送仍是主链路，语音能力可选。", "输入框提供清晰焦点样式与快捷键说明。", "手动提交会议纪要时需要再次确认分享范围。"),
    )),
    ("Agent 与隐私", (
        ("提问‘谁负责接口清单’，回答要能找到原话。", "RAG 只检索当前群和当前成员可见的消息。", "答案下方展示来源卡片，用户可点回原消息。", "我会让群外账号问同样的问题，预期返回拒绝。"),
        ("没有提到预算就不能编造金额。", "模型缺乏证据时固定返回证据不足，不生成引用。", "前端把‘暂无依据’和‘服务暂不可用’分开显示。", "测试会加入一条诱导模型泄露的假指令。"),
        ("摘要重点保留结论和待办，不要堆满聊天流水。", "摘要来源必须来自生成时授权且未撤回的消息。", "摘要按议题、结论、待办三栏展示。", "我会抽样核对每条待办的负责人和截止时间。"),
        ("群主解散讨论室后，向量数据也要清理。", "pgvector 只存授权群消息的向量，解散时移除索引。", "解散后界面不再进入群摘要和问答。", "我会比较删除前后的向量数量与接口权限。"),
    )),
    ("联调与风险", (
        ("今天安排四个人分别从自己的账号发一轮消息。", "先验证 8081 用户服务和 8082 聊天服务，再过网关。", "我会用两个浏览器窗口确认列表实时刷新。", "每次联调记录请求路径和 HTTP 状态，方便定位。"),
        ("Kafka 暂停时，发送成功的消息不能凭空消失。", "MySQL 事务内写消息和 outbox，恢复后补发通知。", "推送延迟时先显示本地发送状态，重连再对账。", "故障演练后确认 outbox 排空且消息没有重复行。"),
        ("Docker 模型下载慢时，聊天功能还要能用。", "Ollama 与消息读写解耦，Agent 不可用时返回明确错误。", "摘要按钮失败后可以重试，不清空当前输入。", "我会分别验证模型停止和恢复两种状态。"),
        ("本地端口冲突需要有清晰指引。", "启动脚本检查端口占用，日志分别写到服务目录。", "前端明确展示代理失败，不把 503 当作验证码错误。", "我会用干净浏览器重新跑一次从注册到发消息。"),
    )),
    ("测试与上线", (
        ("周五 16:00 前交付本地可演示版本。", "周四 18:00 前跑完 Maven 测试和消息基线。", "周五上午确认网页构建和窄屏交互。", "周五 14:00 前做一次权限和可访问性回归。"),
        ("验收采用两个账号直聊、四人讨论室和 100 条以上群消息。", "消息压测记录并发、成功数、缺失数和 p95 延迟。", "长列表分页时滚动位置不能突然跳到顶部。", "我会检查第 1 页到第 3 页是否漏消息。"),
        ("正式环境先完成 TLS 和域名配置。", "数据库仅允许内部访问，密钥不提交到 Git。", "Nginx 只发布网页端口，其余服务在容器网络内。", "备份后还要在隔离环境执行恢复演练。"),
        ("邮箱注册要等 SMTP 真实发信验收后再开放。", "短信供应商尚未接入，不能声称手机号验证码已上线。", "测试码开关只用于本机开发，演示结束立即关闭。", "我会在发布清单里标出这些未完成边界。"),
    )),
    ("复盘与待办", (
        ("今天的第一条结论：先保证聊天闭环，再扩展 AI 能力。", "陈澈负责检查跨节点推送和断线游标，周四 18:00 前完成。", "周予负责群列表、未读标记和来源卡片的交互复查。", "许宁负责群外访问、重新入群和消息撤回测试。"),
        ("第二条结论：摘要内容必须有可点击的证据。", "对被撤回的来源重新计算或失效旧摘要。", "我会在摘要旁加生成时间，避免误读为实时结论。", "验收时随机抽三条回答，逐条核对引用原文。"),
        ("第三条结论：不把本地验证当成公网发布。", "正式部署前补齐 SMTP、短信及可信代理 IP 策略。", "部署说明标注 18080 仅本机访问，避免误开放。", "发布前再跑一次备份恢复演练并留存结果。"),
        ("待办请按负责人认领；有风险今天在群里说明。", "我认领 Kafka 恢复演练和消息落库核对。", "我认领 200% 缩放和键盘可操作性修复。", "我认领四人群聊、无权限账号和历史分页验收。"),
    )),
)

OPENING = (
    "大家好，这是虚构的演示会议：协同办公 v0.9 版本评审。",
    "我先确认用户服务、聊天服务和网关均可用。",
    "网页端已进入讨论室，今天的结论会同步到群里。",
    "本群为测试数据，请不要填写真实客户或公司信息。",
)
CLOSING = (
    "今天讨论到这里，最终上线时间仍以验收结果为准。",
    "我会把消息可靠性和故障恢复结果补到测试记录。",
    "我会整理页面反馈，明天给大家看可点击的改版。",
    "我来核对待办和隐私边界，发现阻断问题继续在群里同步。",
)


def meeting_messages():
    messages = [(i, text) for i, text in enumerate(OPENING)]
    for topic, rounds in SECTIONS:
        for round_messages in rounds:
            messages.extend((i, f"【{topic}】{text}") for i, text in enumerate(round_messages))
    messages.extend((i, text) for i, text in enumerate(CLOSING))
    return messages


def demo_phone(suffix, index):
    # Syntactically valid local test identifiers; no real SMS is sent.
    return f"199{int(suffix, 16) % 10_000_000:07d}{index}"


def fixture_state(path):
    if path.is_file():
        state = json.loads(path.read_text(encoding="utf-8"))
        check(state.get("version") == 1 and len(state.get("people", [])) == 5,
              "existing demo state is invalid; inspect it manually instead of overwriting")
        # Older fixtures were email-only. Keep their accounts, group and message IDs.
        if any("phone" not in person for person in state["people"]):
            for index, person in enumerate(state["people"]):
                person.setdefault("phone", demo_phone(state["suffix"], index))
            path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        return state, False
    suffix = uuid.uuid4().hex[:8]
    state = {"version": 1, "suffix": suffix, "group_name": f"演示会议·协同办公评审-{suffix}",
             "people": [{"nickname": name, "role": role,
                         "phone": demo_phone(suffix, index),
                         "handle": f"meet_{suffix}_{index}",
                         "password": secrets.token_urlsafe(18) + "Aa1"}
                        for index, (name, role) in enumerate(PEOPLE)]}
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        json.dump(state, target, ensure_ascii=False, indent=2)
    return state, True


def account(user_url, person, new_fixture=False):
    login_path = "/api/auth/login/password"
    credentials = {"contactType": "email" if person.get("email") else "phone",
                   "contact": person.get("email") or person["phone"],
                   "password": person["password"]}
    status, response = (401, None) if new_fixture else call(user_url, login_path, body=credentials)
    if status == 401:
        if person.get("email"):
            raise ProbeFailure("old demo account unavailable; the local database may have been reset. "
                               "Move the stale demo-meeting.json aside and rerun to create a fresh fixture")
        sent = expect("development verification code", call(user_url, "/api/auth/codes", body={
            "purpose": "register", "contactType": "phone", "contact": person["phone"]}), 200)
        check(isinstance(sent, dict) and isinstance(sent.get("devCode"), str)
              and len(sent["devCode"]) == 6,
              "devCode unavailable: run the local development stack, not the release stack")
        expect("register " + person["role"], call(user_url, "/api/auth/register", body={
            "nickname": person["nickname"], "handle": person["handle"],
            "phone": person["phone"], "password": person["password"],
            "phoneCode": sent["devCode"]}), 201)
        response = expect("login " + person["role"],
                          call(user_url, login_path, body=credentials), 200)
    elif status != 200:
        raise ProbeFailure(f"login {person['role']}: expected HTTP 200/401, got {status}")
    check(isinstance(response, dict) and isinstance(response.get("token"), str)
          and isinstance(response.get("account"), dict)
          and isinstance(response["account"].get("id"), int),
          "invalid account response for " + person["role"])
    person["id"] = response["account"]["id"]
    person["token"] = response["token"]
    if person.get("email") and response["account"].get("phone") != person["phone"]:
        # Bind on the original account rather than creating a second set of
        # users and losing the existing room/history.
        check(response["account"].get("phone") is None,
              "existing demo account has a different bound phone; inspect it manually")
        sent = expect("development binding code", call(user_url, "/api/auth/codes",
                      person["token"], {"purpose": "bind", "contactType": "phone",
                                         "contact": person["phone"]}), 200)
        check(isinstance(sent, dict) and isinstance(sent.get("devCode"), str)
              and len(sent["devCode"]) == 6,
              "devCode unavailable: run the local development stack")
        bound = expect("bind phone " + person["role"], call(user_url, "/api/auth/bind",
                       person["token"], {"contactType": "phone", "contact": person["phone"],
                                          "password": person["password"],
                                          "code": sent["devCode"]}), 200)
        check(bound.get("phone") == person["phone"], "phone binding did not persist")


def ensure_friend(user_url, owner, friend):
    status, _ = call(user_url, f"/api/contacts/{friend['id']}/access", owner["token"])
    if status == 200:
        return
    check(status == 403, f"friend access check failed: HTTP {status}")
    expect("send friend request", call(user_url, "/api/contacts/requests", owner["token"],
                                       {"handle": friend["handle"]}), 202)
    pending = expect("read friend requests", call(user_url, "/api/contacts/requests?limit=50",
                                                  friend["token"]), 200)
    matches = [row for row in pending["items"] if row["fromId"] == owner["id"]
               and row["toId"] == friend["id"] and row["state"] == "PENDING"]
    check(len(matches) == 1, "expected exactly one pending friend request")
    expect("accept friend request", call(user_url,
           f"/api/contacts/requests/{matches[0]['id']}/accept", friend["token"], body={}), 204)


def group_for(chat_url, state, owner, members):
    groups = expect("list groups", call(chat_url, "/api/chats/groups?limit=50",
                                        owner["token"]), 200)
    existing = [group for group in groups["items"] if group["name"] == state["group_name"]]
    check(len(existing) <= 1, "duplicate demo groups found; inspect them before continuing")
    if existing:
        return existing[0]
    return expect("create demo group", call(chat_url, "/api/chats/groups", owner["token"],
                  {"name": state["group_name"],
                   "friendIds": [member["id"] for member in members]}), 200)


def all_history(chat_url, group_id, token):
    seen = []
    cursor = None
    for _ in range(10):
        params = {"limit": 50}
        if cursor is not None:
            params["beforeId"] = cursor
        page = expect("group history", call(chat_url,
                      f"/api/chats/groups/{group_id}/messages?{urlencode(params)}", token), 200)
        check(isinstance(page, dict) and isinstance(page.get("messages"), list),
              "invalid group history page")
        seen.extend(page["messages"])
        next_cursor = page.get("nextBeforeId")
        if next_cursor is None:
            return seen
        check(isinstance(next_cursor, int) and next_cursor != cursor,
              "group history did not advance")
        cursor = next_cursor
    raise ProbeFailure("group history exceeded ten pages")


def seed(user_url, chat_url, path, progress=print):
    messages = meeting_messages()
    check(len(messages) == 136 and len({body for _, body in messages}) == 136,
          "meeting fixture must contain 136 distinct messages")
    state, new_fixture = fixture_state(path)
    people = state["people"]
    for person in people:
        account(user_url, person, new_fixture)
    owner, members, outsider = people[0], people[1:4], people[4]
    for person in members:
        ensure_friend(user_url, owner, person)
    group = group_for(chat_url, state, owner, members)
    group_id = group["id"]
    ids = {person["id"] for person in people[:4]}
    check(set(group["memberIds"]) == ids and group["ownerId"] == owner["id"],
          "demo group membership differs from four expected accounts")
    status, _ = call(chat_url, f"/api/chats/groups/{group_id}/messages",
                     outsider["token"])
    check(status == 403, f"outsider history must be forbidden, got HTTP {status}")

    sent = []
    for index, (speaker, body) in enumerate(messages):
        result = expect("send demo message " + str(index + 1), call(chat_url,
                        f"/api/chats/groups/{group_id}/messages",
                        people[speaker]["token"], body={
                            "clientMsgId": str(uuid.uuid5(uuid.NAMESPACE_URL,
                                                            f"im-meeting-demo:{state['suffix']}:{index}")),
                            "body": body, "mentionedUserIds": [], "kind": "TEXT",
                        }), 200)
        check(result["body"] == body and result["senderId"] == people[speaker]["id"],
              "server returned a different message for " + str(index + 1))
        sent.append(result["id"])
        if (index + 1) % 20 == 0 or index + 1 == len(messages):
            progress(f"Sent/verified {index + 1}/{len(messages)} messages")
    check(len(set(sent)) == len(messages), "duplicate message IDs in the fixture")
    for person in people[:4]:
        history = all_history(chat_url, group_id, person["token"])
        check(len(history) == len(messages) and {row["id"] for row in history} == set(sent),
              person["nickname"] + " cannot see all 136 messages across pages")
        check(all(row["senderId"] in ids for row in history), "unexpected group sender")
    status, _ = call(chat_url, f"/api/chats/groups/{group_id}/messages/{sent[0]}",
                     outsider["token"])
    check(status == 403 or status == 404, "outsider could read a demo message")
    progress("PASS: 4 members, 136 distinct messages from all members, pagination and outsider access")
    progress(f"Group ID: {group_id}; name: {state['group_name']}")
    progress("Synthetic phone numbers are in the ignored local file: " + str(path))
    for person in people[:4]:
        progress(f"{person['nickname']} ({person['role']}): {person['phone']}")
    progress("On the local web app, choose phone and verification-code login; the development code is shown on screen.")
    progress("No real SMS is sent. Do not publish the credentials file or expose the development code endpoint.")
    return group_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-url", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--chat-url", type=loopback_url, default="http://127.0.0.1:8082")
    parser.add_argument("--state", type=Path, default=STATE,
                        help="ignored local JSON file holding disposable test credentials")
    parser.add_argument("--dry-run", action="store_true", help="show scenario size without writing anything")
    args = parser.parse_args()
    if args.dry_run:
        print(f"{len(PEOPLE)} accounts; {len(PEOPLE)-1} members; {len(meeting_messages())} unique messages")
        print("Topics: " + "、".join(topic for topic, _ in SECTIONS))
        return 0
    if not args.state.resolve().is_relative_to(
            (Path(__file__).resolve().parents[1] / ".local-stack").resolve()):
        parser.error("credential file must remain under the ignored .local-stack directory")
    try:
        seed(args.user_url, args.chat_url, args.state)
    except ProbeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        if args.state.exists():
            print("Partial fixture kept; rerun with the same state to resume: " + str(args.state),
                  file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
