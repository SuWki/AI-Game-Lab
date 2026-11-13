# 首个可玩模板与规格实例化

2026-11-13，B-01 首版切片。实现模板、固定规格消费、独立工作区、构建清单与可运行产物。后续已接 [生产记录 API 和前端生成页面](PRODUCTION_JOBS.md)，尚未接 Builder Agent，不代表 B-02..07 完成。

## 操作

使用 Node 18+，无需安装生成项目的依赖。CLI 从精确 revision 读取 ART-01 保存的 GameSpec：

```powershell
node scripts/production/from-artifact.mjs http://127.0.0.1:8082 ARTIFACT_ID 1 data/generated-games
```

也可以用导出的完整 envelope：

```powershell
node scripts/production/instantiate.mjs GameSpec.json data/generated-games
```

输出独立 UUID 工作区与 buildId。进入该工作区，执行 `node serve-project.mjs 4180`，浏览器打开 http://127.0.0.1:4180。修改源文件后执行 `node build-project.mjs` 生成全新 build 目录，不覆盖旧产物；重新启动预览进程才切到新构建。不允许据此直接写回其他项目。

## 规格和来源

配置字段见 [模板说明](../../templates/survival-web/README.md)。读取 envelope 时核对 kind/schema/revision 与内容 hash；指纹使用 ART-01 排序键 JSON 的兼容子集。若其他语言数值编码导致 hash 不匹配，明确拒绝，不绕过校验。

production-manifest.json 固定规格 ID/revision/hash、模板 version、模板清单/文件 hash。构建先读入源文件快照，再复制到新的目录；build-manifest.json 记录本次运行文件 hash、production manifest hash 和规格引用。预览仅提供该构建清单中的文件，文件被改变时返回 404，不悄悄运行被替换的内容。

实例化不解释任意自然语言，只生成参数支持的可玩切片。其余规格仍保存在 specification.json，并在 README/manifest 中明确未完成。生成不等于规格获接受或 QA 通过。

## 验证

```powershell
node --test scripts/production/template.test.mjs
node scripts/production/acceptance/browser-smoke.mjs WORKSPACE PLAYWRIGHT_INDEX_MJS BROWSER_EXE 4186 EVIDENCE_DIR
```

七项引擎/构建测试覆盖：自然结算与重建状态、种子复现、不同技能规则、边界/资源、死亡与护盾、非法参数/hash、双实例与旧构建保留。验收 runner 在工作区外，浏览器使用真实点击/按键与经过时间，未使用强制结算 hook；smoke 配置限 30 秒以内，分别检查加载、开始、自然结束与重开。它仍是有限场景，不证明完整覆盖、平衡或主观乐趣。

## 与原设计的差异

原 B-01 推荐 Phaser/TypeScript/Vite。本切片先选择零依赖 Canvas/ES modules，以验证规格→工作区→构建→实际游玩的链路。Phaser 作为未来另一模板接入，不改变单后端、共享 Runtime 或产物契约。本轮不引入模板下载器、模型任意命令或新 Agent 类型。
