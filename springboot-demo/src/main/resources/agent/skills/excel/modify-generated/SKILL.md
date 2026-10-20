---
name: excel-modify-generated
description: 修改小五此前生成的 Excel 文件时使用。
version: 1
---

# 修改已生成的 Excel

- 修改小五生成的文件前，必须先调用 read_generated_file 读取；目标须由明确文件 ID 或唯一上下文确定。
- 只做用户要求的改动，尽可能保留其他工作表、数据、公式和格式。
- 使用 generate_excel_workbook 生成新文件，不覆盖原件；标题包含原主题和简短改动说明。
- 读取失败或内容不足时，不得编造表格内容或声称修改完成。
