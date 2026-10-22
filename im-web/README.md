# IM 助手 · 网页端

Vue 3 + TypeScript + Vite。注册、登录、验证码、会话恢复和联系方式绑定通过
`im-user-service` 处理；好友申请和联系人接入 MySQL。单聊消息在
`im-chat-service` 中持久化，并通过 WebSocket 通知另一端刷新；讨论室和待办目前仍是本地演示。

## 本地运行

1. 在 `im-backend` 执行 `docker compose up -d`，启动 MySQL 和 Redis。
2. 在 PowerShell 中启动用户服务（使用 `.env` 里的数据库密码）：

   ```powershell
   cd D:\im-assistant\im-backend
   $env:IM_MYSQL_PASSWORD = Read-Host "输入 .env 中的 IM_MYSQL_PASSWORD"
   .\mvnw.cmd -pl im-user-service spring-boot:run "-Dspring-boot.run.profiles=dev"
   ```

3. 打开另一个 PowerShell 窗口启动聊天服务（8082）：

   ```powershell
   cd D:\im-assistant\im-backend
   $env:IM_MYSQL_PASSWORD = Read-Host "输入 .env 中的 IM_MYSQL_PASSWORD"
   .\mvnw.cmd -pl im-chat-service spring-boot:run
   ```

4. 打开第三个 PowerShell 窗口启动前端：

   ```powershell
   cd D:\im-assistant\im-web
   npm ci
   npm run dev
   ```

打开 `http://localhost:5173/`。Vite 将 `/api/chats`（含 WebSocket）转发到 8082，其他 `/api` 请求转发到 8081。
`npm run build` 检查类型和构建；`npm test` 检查认证和好友接口的数据契约。

## 体验路径

1. 创建账号：填昵称、唯一账号、密码以及手机号或邮箱；服务端先检查格式，
   通过后进入验证码页。两种联系方式都填时，分别获取并填写两条验证码；
   提交时服务端消费验证码并检查唯一性，验证通过才会写入 MySQL。
2. 用已注册的手机号或邮箱，通过密码或验证码登录。刷新页面时，前端使用
   `GET /api/auth/me` 检查当前会话；退出时调用服务端注销接口。
3. 在设置中使用当前密码和验证码补绑另一种联系方式。
4. 在另一个浏览器或标签页注册第二个账号（每个标签页有独立登录会话），
   用已绑定的手机号或唯一账号直接发送好友申请（页面不会透露该账号是否存在）；第二个账号在“好友申请”中同意，然后双方
   刷新联系人列表即可看到真实好友。申请可拒绝或撤回，好友可移除。
5. 点击好友列表中的“发消息”，双方可实时看到单聊消息和未读角标。断线后自动重连并补拉。
   讨论室和待办仍使用本地演示数据。

开发模式的验证码由**后端返回并显示在页面**，没有发送短信或邮件；只能用于
本机联调。此前浏览器中创建的演示账号没有写入 MySQL，需要重新注册测试账号。
注册后如需重新测试，请换一个未使用的唯一账号和联系方式。

服务端不可用时页面会显示错误，不会回退到浏览器演示账号。用户服务已启动且
`/actuator/health` 正常后再试；如果 8081 端口被其他 Java 进程占用，请先处理
端口冲突。登录 token 仅保存在当前标签页的 `sessionStorage`；聊天演示数据按
服务端用户 ID 保存在 `localStorage`，刷新后可恢复。

浏览器支持 Web Speech API 且授予麦克风权限时，可将本人语音转写到输入框，
需手动点击发送。示例译文、讨论线索和消息均为本地演示内容。后续接入顺序：
群聊消息和成员权限 → 通知/待办 → 语音与翻译 → AI 摘要。
