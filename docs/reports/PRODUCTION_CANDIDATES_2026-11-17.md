# 固定候选、下载与预览交付记录

2026-11-17，基于 main `7dd8dd0`，B-06 增量。[接口、边界与恢复约定](../platform/PRODUCTION_CANDIDATES.md)。

## 已实现

- 只对当前完整引擎 PASS、REVIEW 的候选打包，核对实际源码、实际构建、最新构建记录、验收报告、外部固定契约/程序与构建程序 hash。旧基线、漂移和未完成提案不能冒充当前候选。
- 复制明确清单到独立目录后生成 ZIP：9 个源文件、6 个构建文件、验证程序/契约、证据、素材说明、已知问题、README 和零依赖 Node 预览脚本，共 25 个归档条目。清单与 ZIP 分别固定 SHA256；不遍历工作区，未声明的 .env/缓存/日志不导出。
- 同一生产/验收执行幂等；READY 副本独立于原目录。读取/下载/预览重验固定副本，篡改直接拒绝。中断打包不重放，明确 INTERRUPTED；已存在 READY 包重启后留存。
- 受管预览从固定内存字节提供 build，单独回环端口与随机路径；CSP sandbox/来源限制、无 opener/表单权限。最多两实例、有界工作队列、五分钟过期，可主动停止，关闭时清理线程与监听。ZIP 解压后也可用自带 Node 脚本预览相同 build。
- 生产页提供打包、下载、预览和逐候选指纹；预览到期链接从页面清除。Builder 新 Run 用 builder_p003/candidate_package，只保存本地候选，不公开发布、不记录人类接受。新增 production_candidate，共 25 业务表，41 工具、16 Prompt。

## 验证

后端全量 749 项通过；最后将预览工作队列改为有界后，针对到期/端口关闭/容量释放的测试再次通过。前端 78 项及构建通过，Node 12 项通过。首次全量仅新控制器的 Mockito inline 在本机 JDK 24 上不兼容，改为该测试的 subclass mock 后通过；未升级生产依赖或放宽测试断言。

新增用例覆盖固定副本/ZIP一致、排除未声明秘密文件、幂等、源/构建/契约漂移、旧基线拒绝、选中审批后的实际构建、固定文件或归档篡改、只读预览路由、到期/容量/停止、重启中断与留存，以及本机标记/远程地址校验。请求驱动 Runtime 测试真实调用 Node 后执行 candidate_package，返回 READY/hash 且 published=false。

独立数据库和端口的真实 Edge/API 流程：

1. 精确规格生产并独立验收；批准移动速度 210→220 的候选，确认后续打包引用的是新验收执行。
2. 页面点击打包/下载，ZIP 含 25 个条目；所有 payload hash、manifest hash、ZIP hash 匹配。解压到新目录，源码重新构建后的五个输出文件 hash 与固定构建一致。
3. 修改临时原候选目录后，受管预览和下载仍使用固定副本。受管预览实际加载、按键、自然结算、重开通过，浏览器 origin 为 opaque，外部 API 读取受限，未知路径返回 404；点击停止后监听关闭。
4. 解压包使用自带 Node 脚本，在另一个回环端口执行同样的真实浏览器流程，通过。验证中发现便携脚本路径截取的属性调用错误，修正后新增两项 Node 端点回归，重新从新候选下载验证；没有改写旧包。
5. 重启独立后端，候选记录与归档 hash 不变，下载重验通过。旧预览不重挂，重新启动分配新地址；验证后停止并关闭独立服务。

证据：[完整机器记录](evidence/candidate-delivery-2026-11-17/workflow.json)、[重启记录](evidence/candidate-delivery-2026-11-17/restart.json)、[候选页面](evidence/candidate-delivery-2026-11-17/candidate-panel.png)、[受管预览结算](evidence/candidate-delivery-2026-11-17/managed-preview.png)、[解压包预览结算](evidence/candidate-delivery-2026-11-17/portable-preview.png)。未调用付费模型。

## 范围与后续

READY 只表示固定候选可交付，不是 DELIVERED 或人类接受。manifest.humanAccepted=false，scope 仍为 survival-web-engine-v1 六项检查；未自动证明所有 GameSpec、自然语言验收契约、视觉质量或素材权利。源码中硬编码的秘密不能靠文件清单自动识别，分发前仍需审阅。这里不是任意恶意源码的 OS 沙箱。

后续补 B-06 的显式候选接受/拒绝与完整规格覆盖，再以两件存在玩法差异的作品推进 B-07，记录真实供应商、人工介入与费用。完整阶段协调继续按 B-05 剩余退出标准补齐，不因打包成功而提前关闭整个平台制造链。
