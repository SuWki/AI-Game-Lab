# 固定候选、下载与预览 v1

2026-11-17，B-06 增量。新增 production_candidate(id, production_id, validation_run_id, record_json)，同一生产/验收执行唯一，共 25 表。PREPARING/READY/FAILED/INTERRUPTED 是候选记录状态，不改变 Run 或人类接受状态。启动时未完成打包标为 INTERRUPTED，不重放不确定文件操作。

POST /api/productions/{id}/candidates，输入 validationRunId 必须等于当前候选；要求 REVIEW、完整独立引擎 PASS、固定契约/程序引用、源文件与构建指纹一致。复制明确允许的源码、实际已验收构建、规格/来源、验收和绑定证据到独立目录，生成 manifest、几何占位素材说明、已知边界和本地说明，再生成 ZIP。相同引用幂等返回同一候选；源/构建漂移拒绝新打包。READY 副本不随原目录修改而变化。

GET /api/production-candidates/{id} 返回清单/ZIP 哈希和状态；GET /{id}/download 下载，客户端携带本地标记。POST /{id}/preview 启动临时回环预览；POST /{id}/preview/stop 关闭。写入/下载/预览只接受本机与 X-AGL-Local-Test: 1。预览服务只提供包内 build 文件，在不同端口/受限浏览器策略下运行，不执行任意 Node 命令；最多两个实例、五分钟过期，进程关闭时停止。主进程重启不会恢复监听。下载/预览再次验证固定副本与 ZIP 指纹。

ZIP 仅导出固定清单，排除 .env、依赖缓存、日志、任意未声明文件和模型配置。不能自动识别游戏源码本身硬编码的秘密，源码审查仍由操作者负责。原始源码不改写，ZIP 不公开发布。根 manifest 的哈希由数据库记录，文件清单不递归包含 manifest 自身；ZIP 哈希覆盖全部归档字节。

预览地址含随机能力路径，不在根路径开放构建文件。模块加载仅为 sandbox 的 opaque origin 提供 CORS，网络连接只允许当前预览端口；停止/到期清除监听、工作线程和计时任务。独立包脚本使用终端打印的完整地址，不要省略随机路径。UI 预览链接到期自动移除。

验收范围仍为 survival-web-engine-v1 六项检查，不把自然语言 GameSpec、浏览器质量、素材许可或人类接受自动判定为完成。已知问题说明必须随包交付。包内提供零依赖 Node 预览脚本，便于在干净目录解压后运行固定 build；源码可用自带构建脚本重新构建，构建 ID 可变化、文件内容哈希应一致。

Builder 新 Run 可用 candidate_package，绑定自身生产与明确的验收执行；只保存本地候选，不增加审批/发布工具。Prompt 采用 builder_p003，保留旧冻结 Run。全平台 41 工具、16 Prompt。

2026-11-17 后续：[人工评审与规格覆盖](CANDIDATE_HUMAN_REVIEW.md) 新增独立评审表，共 26 表。ZIP 内 humanAccepted=false 是打包时快照，当前意见与门禁须查询 review 接口，不能从 ZIP 推断最新接受状态。评审不修改既有 ZIP、manifest 或引擎证据。
