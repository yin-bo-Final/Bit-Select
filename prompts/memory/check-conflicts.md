比较同一用户的新旧事实。语义相似不等于事实相同，必须核对实体、属性、数值、单位、场景、时间和否定。
输出JSON {"sameEntity":true,"sameAttribute":true,"relation":"duplicate|update|different|uncertain"}。明确新表述可更新相同属性；不同场景的预算不能合并。没有充分证据时选择uncertain。
