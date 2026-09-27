# Qwen3 Token 计数

使用 Qwen 官方 Qwen3-30B-A3B-Instruct-2507 的 ByteLevel BPE 词表和预切分表达式。
`source.json` 记录原始来源与 SHA-256；`LICENSE` 为上游 Apache-2.0 许可。

`prepare.py` 将原始词表转换为后端随包携带的压缩字节 rank 文件。应用启动时不访问外网下载 tokenizer，也不依赖 Python。
`prepare-fixtures.py` 使用上游 Hugging Face Tokenizers 实现生成普通文本、中文、Unicode、长文本和手册计数样本，Java 测试核对一致性。

会话计数额外保留每条消息封装、输出与安全余量；模型或供应商改变聊天模板时仍需校验服务返回的 usage。
用户正文中的特殊标记按普通文字计数，不允许用户自行构造消息角色边界。
