"""Validate the rendered Compose configuration, without exposing environment values."""
import json
import sys

config = json.load(sys.stdin)
assert config["name"] == "bit-select", "Compose 项目名必须隔离"
services = config["services"]
required = {"mysql", "redis", "nacos", "sentinel", "rocketmq-namesrv", "rocketmq-broker", "rustfs", "etcd", "milvus", "neo4j", "tika"}
assert required <= services.keys(), f"缺少组件: {required - services.keys()}"
for name, service in services.items():
    image = service.get("image", "")
    assert ":" in image and not image.endswith(":latest"), f"{name}: 必须固定镜像版本"
    assert service.get("mem_limit"), f"{name}: 必须限制内存"
    for port in service.get("ports", []):
        assert port.get("host_ip") == "127.0.0.1", f"{name}: 只能监听本机回环地址"
    if service.get("restart") != "no":
        assert service.get("healthcheck"), f"{name}: 缺少健康检查"
assert services["redis"]["command"][-1].endswith("noeviction"), "Redis 会话不能被缓存淘汰策略驱逐"
assert services["milvus"]["environment"]["MINIO_ADDRESS"] == "rustfs:9000", "Milvus 必须使用独立 RustFS 存储"
print(f"容器配置通过：{len(services)} 个服务，全部固定版本和本地监听。")
