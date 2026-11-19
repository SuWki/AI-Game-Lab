# 固定候选的浏览器验收

在生产页的规格编辑器选择一项方法为 `browser` 的验收，设置重开首帧字段和期望数值，然后保存新规格并生产固定候选。建议每项只表达一个断言，例如“首帧热量为 0”。只有自然语言预期的条目仍可人工评审。旧规格和旧候选自然保留当时的检查定义。

目前支持 `survival-web-restart-frame-v1`。候选使用 1–30 秒的回合。适配器可观察生命、热量或能量、得分和显示时间。规格中的可选配置示例为 `"browserProbe":{"profile":"survival-web-restart-frame-v1","field":"heat","expected":0}`。热量或能量与实际机制不匹配时，该项显示未测。

## 运行与回查

从仓库根目录运行，替换候选 ID、已安装 Playwright 的 `index.mjs` 和 Chromium 浏览器路径。使用 Playwright 已安装的 Chromium 时可省略浏览器路径。

```text
node scripts/production/acceptance/candidate-browser.mjs CANDIDATE_ID "PLAYWRIGHT_INDEX" "BROWSER_EXECUTABLE"
```

脚本向本地 API 请求验收计划和固定包预览，在独立浏览器上下文打开固定构建。它点击开始，等待自然结算，点击重新开始，并读取首个更新的 HUD。只向该预览路径的固定资源发送页面请求。记录包括操作、浏览器版本、HUD 原文、观察时间、规格修订与指纹、固定清单和 ZIP 指纹。若执行在观察前超时或出现页面异常，此次不提交结果，页面保留已有记录。限时预览由原有 TTL 关闭，也可从生产页结束。

后台从固定包读取期望值，根据 HUD 原文计算通过和失败。未映射项保持 `NOT_TESTED`，每个候选独立建立证据。执行 ID 可用 `GET /api/production-candidates/{id}/browser-evidence/{executionId}` 回查。提交同一执行和相同观察幂等。证据来源是本机运行的适配器，写入凭证使用现有本机请求约束。

运行完在候选评审区点击“重新载入最新评审”。最新浏览器报告出现失败时会关闭该候选的交付门禁，人工意见和历史回执仍可查询。浏览器通过只覆盖映射的字段，交付还需要当前候选的完整人工观察及明确接受。新候选以未测开始，旧证据和 ZIP 可分别回查。

模拟 QA 的判定统计另有 `buildShareExperimentId`、`buildShareTotalGames` 和 `buildShareLargestBuildGames`。占比口径是最多流派的局数除以总局数，描述的是实验样本分布。规则和阈值沿用原设计，既有判定记录按保存时的口径解释。
