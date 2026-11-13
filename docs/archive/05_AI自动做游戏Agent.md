# AI Autonomous Game Builder 设计文档

> 2026-11-12 范围与状态说明：本文为概念归档。正式生产设计、工具权限、恢复与验收见 [Builder 模块](../projects/05_AI_Auto_Game_Developer/README.md)。不直接采用本文任意文件工具、失败自动全量回滚或未经校准的 Release Confidence 示例。

## 1. 目标

让 AI 从自然语言目标开始，自主完成：

```text
需求
↓
Game Design
↓
Technical Design
↓
Code
↓
Build
↓
Run
↓
Test
↓
Fix
↓
Playtest
↓
Iterate
↓
Release Candidate
```

## 2. 第一阶段原则

不要追求完全自动。第一版只支持一个受控模板。

建议：
- Phaser 3
- TypeScript
- Vite

## 3. Tools

### Project
- create_project
- list_files
- read_file
- write_file
- patch_file

### Runtime
- start_game
- stop_game
- get_console_log
- take_screenshot

### Test
- run_unit_test
- run_game_test
- inspect_runtime

### Git
- git_status
- git_diff
- git_commit
- git_create_branch

## 4. 工作循环
```text
Goal
↓
Plan
↓
Create Skeleton
↓
Implement
↓
Build
↓
Run
↓
Observe
↓
Fix
↓
Test
↓
Play
↓
Improve
```

## 5. Definition of Done

必须：
- Build 成功
- 能启动
- 无致命 Console Error
- 基础玩法可运行
- 玩家可以完成一局
- 测试通过
- README 存在

## 6. 防止代码失控

每次修改：
`git diff → static check → build → test`

失败自动回滚。

每轮默认限制：
- 最多 20 个文件
- 最多 5000 行 diff
- 最多 5 次自动修复

超过限制进入人工审批。

## 7. 自动 Playtest

至少运行：
- Random
- Conservative
- Aggressive

统计：
- 完成率
- 死亡率
- 游戏时长
- Bug
- 卡死
- 数值异常

## 8. AI 自评

禁止“应该没问题”。必须输出：
```text
Build: PASS
Runtime: PASS
Test: 87/90
Playtest: PASS
Known Issues: 3
Release Confidence: 0.78
```

## 9. 示例任务

> 制作一个 5 分钟一局的 2D 自动射击 Roguelite。
>
> 要求：
> 1. 玩家自动攻击
> 2. 每 60 秒选择一次升级
> 3. 3 种 Build
> 4. 5 个阶段
> 5. 最终 Boss
> 6. 每局不超过 10 分钟

Agent 应生成：
- game design
- config
- source code
- tests
- README
- build artifact
- playtest report

## 10. 终极目标

```text
User
 ↓
Game Designer Agent
 ↓
Technical Agent
 ↓
Coder Agent
 ↓
QA Team
 ↓
Playtest Agents
 ↓
Release Manager
 ↓
Playable Game
```

这是一套小型 AI 游戏研发团队，而不是简单的代码生成器。
