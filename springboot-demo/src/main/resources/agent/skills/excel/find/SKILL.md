---
name: excel-find
description: 查找、核实或找回已生成文件时使用。
version: 1
---

# 查找已生成文件

- 用户询问文件位置、是否生成或要找回时，先调用 list_generated_files 查询。
- 只根据工具返回结果报告文件名、状态和可用入口；不得根据历史话术推断文件存在。
