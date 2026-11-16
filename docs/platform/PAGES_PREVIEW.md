# GitHub Pages 静态预览

2026-11-16。用户选择发布静态前端预览，后端继续本地运行。

地址：[AI Game Lab 预览](https://suwki.github.io/AI-Game-Lab/)。这是五模块能力、实施边界及真实操作截图的只读展示，不承载在线任务、模型调用、Java 后端或 SQLite，也不填充虚构仪表盘数据。完整平台继续按仓库 README 在本地启动。

## 修复原因与配置

仓库 Pages API 显示 build_type=workflow，但此前只有 Platform checks，没有上传/部署 Pages 的工作流，因而未形成站点发布。Vite 默认根路径也不适用于本仓库 `/AI-Game-Lab/` 子路径；完整平台的 BrowserRouter 与 `/api` 不能直接被当作 Pages 后端服务。参见 [GitHub 发布源说明](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site) 与 [Vite 部署说明](https://vite.dev/guide/static-deploy.html)。

新增 `.github/workflows/pages.yml`：PR 验证静态构建，main push 或手动触发时上传 frontend/dist，使用受限 pages:write / id-token:write 权限发布到 github-pages 环境。仓库 Pages Source 保持 GitHub Actions。

构建参数 `VITE_STATIC_PREVIEW=true` 启用独立 StaticPreview 入口；`VITE_BASE_PATH=/<仓库名称>/` 设置资源前缀。预览使用 hash 导航，刷新各模块不会请求不存在的服务器路由。没有配置时仍运行原 App、BrowserRouter 与 Vite 本地代理。

PowerShell 本地检查：

```powershell
cd frontend
$env:VITE_STATIC_PREVIEW='true'
$env:VITE_BASE_PATH='/AI-Game-Lab/'
npm run build
npm run preview -- --host 127.0.0.1 --port 5174
```

预览地址为 `http://127.0.0.1:5174/AI-Game-Lab/`。恢复普通构建前移除两个环境变量；开发服务器默认仍用 5173。Pages 不配置模型密钥，也不暴露本地 API。

## 验证与另一个 CI 问题

Edge 检查子路径加载、五个模块切换、hash 刷新、全部图片、390px 窄屏无横向溢出；页面未发起 API/SSE 请求，无脚本错误或 HTTP 失败。见 [本地记录](../reports/evidence/pages-2026-11-16/local.json)、[桌面截图](../reports/evidence/pages-2026-11-16/desktop.png)、[手机截图](../reports/evidence/pages-2026-11-16/mobile.png)。静态构建和原前端 71 项测试通过。

此前 main 的 Platform checks 另有独立失败：RunControlServiceTest 在看到 RUNNING 后立即取消，却断言至少存在一个 step；合法的早取消可能发生在首个 step 插入前。已用 CountDownLatch 等到工具实际执行，再取消并释放工具，验证线程终止、状态不被后续完成覆盖、step 在预算内。修复测试同步条件，不改变生产取消语义。修复后后端全量 716 项通过。
