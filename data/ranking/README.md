# LambdaMART 离线训练

本目录实现真实的 LightGBM `lambdarank` 梯度提升树训练，并导出供 Java 推理的 `dump_model`。它需要从检索链路导出的候选特征和相关性标签，不会把手写权重声称为训练得到的 LambdaMART。

特征顺序固定为 `bm25Score, cosineScore, rerankScore, exactProductMatch`；数值必须与 Java 在线检索代码的归一化与计算方式一致。数据采用每候选一行 JSONL：

```json
{"queryId":"q-0001","productGroup":"product-family-01","candidateId":"chunk-001","features":[3.2,0.72,0.86,1],"label":3,"labelSource":"human","featureSource":"retrieval"}
```

`label` 为 0（无关）至 3（高度相关）。`labelSource` 必须明确填写 `human`、`reranker-weak`、`catalog-target`（根据目录中目标商品身份生成的规则弱标注）或 `synthetic`，不混合标签来源。`productGroup` 是候选实际所属商品或商品族，用来隔离产品；也可按互斥品类隔离，不能简单填写随机行号规避隔离。每个 query 至少两条候选、两个不同标签等级，至少六个互不重叠的 query/product 连通组。六个组会划为 4 个训练、1 个验证、1 个测试。

同一 query 或商品族之间存在联系的数据整体分到同一集合。训练、验证、测试采用约 60% / 20% / 20% 的连通组划分；测试集从不参与提前停止。若所有查询都召回同样的热门商品，连通图可能只有一个组，此时脚本拒绝训练，需要先按商品族划分检索候选池再采集数据。不能将同一商品、同一问题的多个 chunk 随机拆到测试集。

```powershell
python -m venv .local/ranking-venv
.local/ranking-venv/Scripts/python.exe -m pip install -r data/ranking/requirements.txt
.local/ranking-venv/Scripts/python.exe data/ranking/train.py --input data/ranking/real-candidates.jsonl --output-dir data/ranking --label-source catalog-target
```

输出 `model.json`、`evaluation.json`、`equivalence-fixtures.json`。评估报告包含数据 SHA-256、标签来源、实际划分的 query 与商品族、NDCG@5/10、单独 reranker 基线及实际差值。差值可能为零或负数，脚本不隐藏退化结果。模型元数据和报告都会明确说明：弱监督/合成标签只用于验证训练管线，不能证明人工评测质量提升；尤其以 reranker 打分生成标签时，标签和 reranker 输入特征来自同一教师，不能作为独立质量证据。

数据指纹按 JSONL 的实际 UTF-8 字节计算。导出器、合成数据生成器与 `.gitattributes` 将 `data/ranking/**/*.jsonl` 固定为 LF，不受 Windows `core.autocrlf` 影响；原始手册的换行不在这条规则内。训练入口拒绝 CRLF 数据，避免先训练再被 Git 转换而失去可复现指纹。执行 `python data/ranking/verify-artifacts.py` 可在全新克隆中用标准库核对正式及合成数据与 `evaluation.json`、`model.json` 元数据指纹、标签来源和划分行数，不调用 API、不重新训练。

`catalog-target` 标签也不是人工评估：针对特定商品自动生成的问题可能很容易由 `exactProductMatch` 识别目标，不能代表开放式导购问题。它必须保留独立品类测试及该限制说明，不因指标较高宣称模型优于通用 reranker。

## 当前默认制品

`real-candidates.jsonl` 已保存 200 个商品指向查询的 2,364 条真实候选；Embedding 与重排来自硅基流动 Qwen3-Embedding-4B / Qwen3-Reranker-4B，BM25 来自当前知识库。候选池在查询对应的互斥品类内构建，以保持商品隔离。标签使用目录规则：目标商品 3、同类型商品 1、其他候选 0，全部标为 `catalog-target`；它们不是人工标签。

默认模型的训练集为 135 个查询、4 个品类；验证集为 `living` 的 30 个查询；测试集为 `office` 的 35 个查询。验证集提前停止选择了 1 棵树，保留实际选择结果，不人为增加树数。该弱标注留出集的 NDCG@5 为 0.979061，reranker 基线为 0.962848；NDCG@10 为 0.981392，基线为 0.942053。完整数值、数据指纹及划分清单见 [evaluation.json](evaluation.json)，实际导出来源见 [export-report.json](export-report.json)。这些数值仅描述这批商品指向任务，不证明对真实开放式对话的排序质量提升。

`equivalence-fixtures.json` 的预期分数直接由原生 LightGBM 产生，包含阈值两侧与阈值相等的边界样本，用于 Java 树遍历实现的数值一致性测试。该模型只使用有限数值特征和 `<=` 分裂，禁用缺失值分支与类别分裂。

```powershell
python data/ranking/generate-fixture.py
.local/ranking-venv/Scripts/python.exe data/ranking/train.py --input data/ranking/fixtures/synthetic-candidates.jsonl --output-dir data/ranking/fixtures --label-source synthetic --allow-synthetic
```

上述命令只产生明确标注的合成测试制品，强制输出到 `fixtures` 目录。合成模型不得复制为默认线上 `data/ranking/model.json`。真实模型未提供前，Java 使用 reranker 基线，并在检索追踪中展示当前排序模式。

依据：[LightGBM 排序参数](https://lightgbm.readthedocs.io/en/stable/Parameters.html)、[LGBMRanker 的 query group 约束](https://lightgbm.readthedocs.io/en/stable/pythonapi/lightgbm.LGBMRanker.html)。
