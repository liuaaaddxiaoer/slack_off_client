# AGENTS.md — 会话交接记录

> 本文件由会话交接生成,记录 DSH 本地配置调查结论与待办,供后续新会话继承上下文(「大概知道」上一个会话的任务)。SlackOff 项目自身的业务约定待补。

## 工作区

- 根目录:`/Users/mac/Documents/ChatGPT/slack_off`
- 跨平台项目:iOS(`SlackOff` + `SlackOff.xcodeproj` + `SlackOffTests` + `SlackOffUITests`)/ Android(`android/`)
- 构建配置:`project.yml`(XcodeGen)、`README.md`
- 其它脚本:`test_newapi_vision.py`、`test_vision_models.sh`(视觉模型测试)

## 本会话主题:调查本地 DSH 默认预设为何是「梁神模式」

### 结论

1. **原因**:`~/.dsh/settings.yaml` 第 4 行 `agent-presets.default: liangshen`;`~/.dsh/.agent-presets/liangshen/preset.yml` 的 `name` 字段 =「梁神模式」(`liangshen` = 拼音「梁神」,玩梗命名)。该预设是第三方预设,改编自内置 Minimal/Standard,并合并 `xiaobright/dsh-anchored-standard` 的两阶段隔离扩展。

2. **梁神模式机制**:两阶段输入引导——
   - phase 1:只给模型 `bash` + `str_replace_editor` + 一行 persona(锚定 Minimal 的 RL 训练表面),无 runtime contexts;
   - 首轮后晋升 phase 2(`promoteAfterFirstResponse`):切 PTC 模式(`run_code`),开放 Standard 全能力;
   - compaction 后回退受控阶段(两工具 + 核心集)直到重新晋升;
   - 持久 `bash` 全程保留(逐字节 Minimal 的代价)。

3. **不能在对话进行中切换预设**:会话 composition 随会话创建即锁定,host 对切换请求返回 `agent-preset-locked`;预设只在「新建会话」界面(工作区选择器旁的 chip)可选;`settings.yaml` 的 `default` / GUI「设为默认」只影响**新会话**,改不了当前会话。

4. **单会话开多子任务**:`subagent`(并行后台,各自独立上下文)/ `subagent_fork`(继承当前对话已完成轮次)/ `workflow`(JS 脚本编排大批量 fan-out)/ `ralph`(fresh-agent 迭代,需用户明确要求)。梁神 phase 2 才开放这些工具。

5. **跨会话任务交接对比**:
   - **fork(会话分叉)**= 原样 seed 父会话「已完成轮次前缀」(`balanced completed-turn prefix`,**lossless 不压缩**),新会话开局继承父会话全部上下文占用(父 80% → 子 80%,只剩 20%);梁神 fork 后很快触发 compaction 回退受控阶段。适合「完整接续」。
   - **AGENTS.md(`agent-instructions` 插件)**= 轻量交接,新会话自动加载并注入 `Instructions from: AGENTS.md`。候选文件 `['AGENTS.md','CLAUDE.md']`;位置:项目根(项目级)/ 子目录(按需)/ `~/.dsh/AGENTS.md`(全局);支持热重载(改了即 `Updated instructions`);梁神配置 `maxBytes: 65536`。适合「大概知道、轻量重启」。

## 待办 / 当前状态

- [ ] **`~/.dsh/settings.yaml` 第 4 行仍是 `default: liangshen`,未改**。曾考虑改成 `standard`(让新会话默认标准模式,全功能、无「开局裁到两工具」副作用),中途转向了解机制,最终未动 → **下次会话需用户拍板**:改 `standard` 还是保留 `liangshen`。
- 本会话仅做调查 + 写本 `AGENTS.md`,无其它代码改动。

## 相关文件

- 本地配置:`~/.dsh/settings.yaml`、`~/.dsh/.agent-presets/liangshen/`(`preset.yml` / `agent.cordis.yml` / `tool-bootstrap.mjs` / `custom-bash.mjs` / `NOTICE`)
- DSH 源码:`/Users/mac/Desktop/github/deepseek-harness/`(`packages/preset/agent-presets`、`packages/context/agent-instructions`、`packages/client/ui-workspace` 的 `forkSession`、`packages/core/agent` 的 fork `seed`)
